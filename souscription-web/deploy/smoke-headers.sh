#!/usr/bin/env bash
# Smoke-test the deployed static artifact's cache policy and probes.
# Usage: smoke-headers.sh <base-url>
set -uo pipefail

BASE="${1:-}"
if [ -z "$BASE" ]; then
  echo "usage: $0 <base-url>" >&2
  exit 2
fi
BASE="${BASE%/}"
fail=0

check() {
  if [ "$1" -eq 0 ]; then
    echo "PASS: $2"
  else
    echo "FAIL: $2" >&2
    fail=1
  fi
}

# 1. index.html must not be cached
curl -sfSI "$BASE/index.html" | grep -iq 'cache-control:.*no-store'
check $? "/index.html returns Cache-Control: no-store"

# 2. a hashed asset must be immutable
asset=$(curl -sfS "$BASE/index.html" | grep -o '[A-Za-z0-9._/-]*\.js' | head -1)
if [ -z "$asset" ]; then
  echo "FAIL: no hashed js asset referenced by /index.html" >&2
  fail=1
else
  curl -sfSI "$BASE/${asset#/}" | grep -iq 'cache-control:.*immutable'
  check $? "hashed asset $asset returns Cache-Control: ... immutable"

  # 2b. repeat visit: the body must not be retransferred.
  # `immutable` means the browser does not even revalidate, so the assertion is satisfied
  # either by the directive itself or by a 304 on a conditional request.
  asset_headers=$(curl -sfSI "$BASE/${asset#/}")
  etag=$(echo "$asset_headers" | grep -i '^etag:' | head -1 | tr -d '\r' | cut -d' ' -f2-)
  lastmod=$(echo "$asset_headers" | grep -i '^last-modified:' | head -1 | tr -d '\r' | cut -d' ' -f2-)
  cond=()
  [ -n "$etag" ] && cond+=(-H "If-None-Match: $etag")
  [ -n "$lastmod" ] && cond+=(-H "If-Modified-Since: $lastmod")
  if [ ${#cond[@]} -eq 0 ]; then
    echo "$asset_headers" | grep -iq 'cache-control:.*immutable'
    check $? "repeat visit: $asset is immutable so no revalidation occurs"
  else
    revisit=$(curl -s -o /dev/null -w '%{http_code} %{size_download}' "${cond[@]}" "$BASE/${asset#/}")
    code_revisit=${revisit%% *}
    size_revisit=${revisit##* }
    { [ "$code_revisit" = "304" ] && [ "$size_revisit" = "0" ]; }
    check $? "repeat visit: conditional GET $asset returns 304 with 0 bytes (got $revisit)"
  fi
fi

# 2c. a deep SPA route served by the index.html fallback must inherit no-store
curl -sfSI "$BASE/souscription/recapitulatif" | grep -iq 'cache-control:.*no-store'
check $? "deep route /souscription/recapitulatif returns Cache-Control: no-store"

# 2d. version.json must never be cached, otherwise a deployment is never picked up
curl -sfSI "$BASE/version.json" | grep -iq 'cache-control:.*no-store'
check $? "/version.json returns Cache-Control: no-store"

# 3. health probe
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/healthz")
[ "$code" = "200" ]
check $? "GET /healthz returns 200 (got $code)"

# 4. version signal (W9-T04): /version.json carries the commit sha and the build date of the
# deployed artifact. EXPECTED_RELEASE (CI: the commit sha passed to `docker build`) pins the exact
# value; without it the release must still be a commit sha, never the `local`/`unknown` defaults.
version_body=$(curl -sfS "$BASE/version.json")
json_field() { echo "$version_body" | tr -d ' ' | tr ',{}' '\n\n\n' | grep "^.$1.:" | cut -d: -f2- | tr -d '"'; }
release=$(json_field release)
built_at=$(json_field builtAt)
if [ -n "${EXPECTED_RELEASE:-}" ]; then
  [ "$release" = "$EXPECTED_RELEASE" ]
  check $? "GET /version.json release is the expected commit sha $EXPECTED_RELEASE (got ${release:-none})"
else
  echo "$release" | grep -Eq '^[0-9a-f]{7,40}$'
  check $? "GET /version.json release is a commit sha (got ${release:-none})"
fi
echo "$built_at" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z$'
check $? "GET /version.json builtAt is an ISO-8601 build date (got ${built_at:-none})"

# 5. security headers equivalent to SecurityFilter.java, plus HSTS
headers=$(curl -sfSI "$BASE/index.html")

echo "$headers" | grep -iq '^x-content-type-options:[[:space:]]*nosniff'
check $? "X-Content-Type-Options: nosniff"

echo "$headers" | grep -iq '^x-frame-options:[[:space:]]*DENY'
check $? "X-Frame-Options: DENY"

echo "$headers" | grep -iq '^referrer-policy:[[:space:]]*no-referrer'
check $? "Referrer-Policy: no-referrer"

echo "$headers" | grep -iq '^strict-transport-security:.*max-age=31536000'
check $? "Strict-Transport-Security: max-age=31536000"

csp=$(echo "$headers" | grep -i '^content-security-policy:' | head -1)
[ -n "$csp" ]
check $? "Content-Security-Policy header present"

for directive in "default-src 'self'" "script-src 'self'" "style-src 'self'" \
                 "img-src 'self'" "connect-src 'self'" "object-src 'none'" \
                 "base-uri 'none'" "frame-ancestors 'none'" "form-action 'self'"; do
  echo "$csp" | grep -Fq "$directive"
  check $? "CSP contains \"$directive\""
done

# 6. the CSP must not be loosened
echo "$csp" | grep -Eiq "unsafe-inline|unsafe-eval|\*"
if [ $? -eq 0 ]; then
  echo "FAIL: CSP contains unsafe-inline, unsafe-eval or a wildcard source: $csp" >&2
  fail=1
else
  echo "PASS: CSP free of unsafe-inline, unsafe-eval and wildcard sources"
fi

# 6b. the style-src nonce is fresh per response and is the one index.html hands to Angular
# (ngCspNonce), otherwise every component stylesheet is blocked by the policy.
doc=$(curl -sfS -D - "$BASE/index.html")
header_nonce=$(echo "$doc" | grep -i '^content-security-policy:' | grep -o "'nonce-[A-Za-z0-9+/=_-]*'" | head -1 | sed "s/'nonce-\(.*\)'/\1/")
body_nonce=$(echo "$doc" | grep -io 'ngcspnonce="[^"]*"' | head -1 | sed 's/.*="\(.*\)"/\1/')
{ [ -n "$header_nonce" ] && [ "$header_nonce" = "$body_nonce" ] && [ "$body_nonce" != "__CSP_NONCE__" ]; }
check $? "CSP style-src nonce matches the ngCspNonce of index.html (header=${header_nonce:-none})"
second_nonce=$(curl -sfSI "$BASE/index.html" | grep -i '^content-security-policy:' | grep -o "'nonce-[^']*'" | head -1)
[ -n "$second_nonce" ] && [ "$second_nonce" != "'nonce-$header_nonce'" ]
check $? "CSP nonce differs between two responses"

# 7. the CSRF cookie of GET /api/v1/csrf-token must be script-readable and SameSite=Strict.
# /api/v1 is served by the gateway, not by this static container: when no gateway answers at
# the base URL the section is skipped, so the standalone-container invocation stays green.
csrf_headers=$(curl -sSI "$BASE/api/v1/csrf-token" 2>/dev/null)
csrf_code=$(echo "$csrf_headers" | grep -i '^HTTP/' | tail -1 | awk '{print $2}')
# A standalone SPA container either has no such route (404) or answers the index.html fallback
# (text/html), and an unreachable base URL yields no status line at all.
if [ -z "$csrf_code" ] || [ "$csrf_code" = "404" ] \
   || echo "$csrf_headers" | grep -iq '^content-type:[[:space:]]*text/html'; then
  echo "SKIP: no gateway at $BASE (GET /api/v1/csrf-token -> ${csrf_code:-unreachable}); CSRF cookie checks skipped"
else
  csrf_cookie=$(echo "$csrf_headers" | grep -i '^set-cookie:.*XSRF-TOKEN=' | head -1)
  [ -n "$csrf_cookie" ]
  check $? "GET /api/v1/csrf-token sets the XSRF-TOKEN cookie"

  echo "$csrf_cookie" | grep -iq 'samesite=strict'
  check $? "XSRF-TOKEN cookie carries SameSite=Strict"

  echo "$csrf_cookie" | grep -iq 'secure'
  check $? "XSRF-TOKEN cookie carries Secure"

  if echo "$csrf_cookie" | grep -iq 'httponly'; then
    echo "FAIL: the XSRF-TOKEN cookie is HttpOnly and cannot be read by the SPA" >&2
    fail=1
  else
    echo "PASS: XSRF-TOKEN cookie is readable by script (no HttpOnly)"
  fi
fi

# 8. Surface of the static server (W6-T06): only the build assets, /healthz and /version.json
# are exposed. Any other path is answered by the index.html SPA fallback, never by a directory
# listing and never by a server-side resource.
for probe in "/WEB-INF/web.xml" "/META-INF/context.xml" "/../etc/passwd" "/server-status"; do
  probe_headers=$(curl -sSI "$BASE$probe")
  probe_code=$(echo "$probe_headers" | grep -i '^HTTP/' | tail -1 | awk '{print $2}')
  echo "$probe_headers" | grep -iq '^content-type:[[:space:]]*text/html'
  html=$?
  { [ "$probe_code" = "200" ] && [ "$html" -eq 0 ]; } || [ "$probe_code" = "404" ]
  check $? "$probe is answered by the SPA fallback or 404, not by a server resource (got ${probe_code:-unreachable})"
done

# 8b. no directory listing on a directory path
for dir in "/" "/assets/" "/media/"; do
  body=$(curl -sS "$BASE$dir")
  if echo "$body" | grep -Eiq '<title>Index of|autoindex'; then
    echo "FAIL: $dir serves a directory listing" >&2
    fail=1
  else
    echo "PASS: $dir serves no directory listing"
  fi
done

# 8c. the three backend services are not reachable through this origin: only /api/ is routed
# (to souscription-gateway, deploy/ingress.yaml), and the service ports are not exposed.
for internal in "/souscription-service" "/souscription-catalogue-service" "/souscription-simulation-service"; do
  internal_code=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE$internal")
  internal_type=$(curl -sSI "$BASE$internal" | grep -i '^content-type:' | head -1)
  { [ "$internal_code" = "404" ]; } || echo "$internal_type" | grep -iq 'text/html'
  check $? "$internal is not routed from the public origin (got $internal_code)"
done

# 8d. same origin: a response of this origin must never carry a CORS header (OD-004)
if curl -sSI "$BASE/index.html" | grep -iq '^access-control-'; then
  echo "FAIL: the static server emits an Access-Control header; the origin model is same-origin" >&2
  fail=1
else
  echo "PASS: no Access-Control header emitted (same-origin model, OD-004)"
fi

exit $fail
