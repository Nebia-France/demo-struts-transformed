#!/usr/bin/env bash
# W6-T05 - one complete subscription journey through the public /api/v1 surface.
#
# catalogue -> product -> CSRF pair -> draft -> subscriber -> simulation -> attach -> recap ->
# confirmation -> confirmed subscription. Used against both development flows:
#   (a) quarkus:dev + contract stubs:   BASE_URL=http://127.0.0.1:18080 (default)
#   (b) docker-compose.dev.yml topology: BASE_URL=http://127.0.0.1:8088 (shared same-origin ingress)
# Exit code 0 = every step answered its contract status. Requires curl and sed only.

set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:18080}"
CSRF_COOKIE_NAME="${CSRF_COOKIE_NAME:-XSRF-TOKEN}"
CSRF_HEADER_NAME="${CSRF_HEADER_NAME:-X-CSRF-Token}"
PRODUCT="${PRODUCT:-PRUDENT}"
JAR="$(mktemp)"
BODY="$(mktemp)"
trap 'rm -f "${JAR}" "${BODY}"' EXIT

step() {
  local expected="$1" label="$2" method="$3" path="$4" data="${5:-}"
  local args=(--silent --show-error --connect-timeout 5 --max-time 30 -o "${BODY}" -w '%{http_code}' -X "${method}"
    -b "${JAR}" -c "${JAR}" -H 'Accept: application/json')
  if [[ -n "${data}" ]]; then
    args+=(-H 'Content-Type: application/json' -H "${CSRF_HEADER_NAME}: ${TOKEN:-}" --data "${data}")
  fi
  if [[ -n "${IDEMPOTENCY_KEY:-}" && "${path}" == */confirmation ]]; then
    args+=(-H "Idempotency-Key: ${IDEMPOTENCY_KEY}")
  fi
  local status
  status="$(curl "${args[@]}" "${BASE_URL}${path}")"
  if [[ ! " ${expected} " == *" ${status} "* ]]; then
    echo "FAIL: ${label}: ${method} ${path} -> ${status}, expected ${expected}" >&2
    cat "${BODY}" >&2; echo >&2
    exit 1
  fi
  echo "OK   ${status} ${label}" >&2
}

field() { sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" "${BODY}" | head -1; }

step 200 "catalogue" GET /api/v1/products
step 200 "product detail" GET "/api/v1/products/${PRODUCT}"
step 200 "CSRF token (double-submit cookie ${CSRF_COOKIE_NAME})" GET /api/v1/csrf-token
TOKEN="$(field token)"
[[ -n "${TOKEN}" ]] || { echo "FAIL: no CSRF token issued" >&2; exit 1; }
step 201 "draft started" POST /api/v1/drafts "{\"productCode\":\"${PRODUCT}\"}"
DRAFT="$(field draftId)"
[[ -n "${DRAFT}" ]] || { echo "FAIL: no draftId returned" >&2; exit 1; }
step 200 "subscriber captured" PUT "/api/v1/drafts/${DRAFT}/subscriber" \
  '{"firstName":"Alice","lastName":"Durand","email":"alice@example.com","birthDate":"1990-01-15"}'
step 200 "simulation computed" POST /api/v1/simulations \
  "{\"productCode\":\"${PRODUCT}\",\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\",\"durationYears\":10}"
step 200 "simulation attached" PUT "/api/v1/drafts/${DRAFT}/simulation" \
  '{"initialAmount":"1000.00","monthlyAmount":"100.00","durationYears":10}'
step 200 "recap" GET "/api/v1/drafts/${DRAFT}"
KEY="$(field confirmationKey)"
[[ -n "${KEY}" ]] || { echo "FAIL: no confirmationKey on the draft" >&2; exit 1; }
IDEMPOTENCY_KEY="${KEY}"
step "200 201" "confirmation" POST "/api/v1/drafts/${DRAFT}/confirmation" "{\"confirmationKey\":\"${KEY}\"}"
REFERENCE="$(field reference)"
[[ -n "${REFERENCE}" ]] || { echo "FAIL: no subscription reference returned" >&2; exit 1; }
step 200 "confirmed subscription" GET "/api/v1/subscriptions/${REFERENCE}"
echo "JOURNEY OK: subscription ${REFERENCE} via ${BASE_URL}" >&2
