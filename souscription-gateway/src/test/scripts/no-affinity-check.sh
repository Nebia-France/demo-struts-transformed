#!/usr/bin/env bash
# W3-T01 / NFG-012 - round-robin statelessness check.
#
# Runs one complete subscription journey while alternating between two gateway instances with no
# session affinity. Every request of the journey hits a different instance than the previous one;
# the journey must succeed end to end, which it can only do if the gateway holds no server-side
# conversational state.
#
# Prerequisites: two instances listening on ${PORT_A} and ${PORT_B} (default 8080 / 8081), both
# wired to the same souscription-service. Executed for real in plan 24; here it is only required
# to be syntactically valid (bash -n).
#
# Exit code 0 = 0 response attributable to a missing affinity (no 4xx/5xx on the journey).

set -euo pipefail

HOST="${HOST:-localhost}"
PORT_A="${PORT_A:-8080}"
PORT_B="${PORT_B:-8081}"
PRODUCT_CODE="${PRODUCT_CODE:-PRUDENT}"
CSRF_COOKIE_NAME="${CSRF_COOKIE_NAME:-XSRF-TOKEN}"
CSRF_HEADER_NAME="${CSRF_HEADER_NAME:-X-CSRF-Token}"

instances=("http://${HOST}:${PORT_A}" "http://${HOST}:${PORT_B}")
# The call counter lives in a file, not in a variable: every call is made inside a command
# substitution, whose increments are lost with the subshell. Keeping it in a variable silently
# sent several consecutive calls to the same instance, which is exactly what this check must not do.
counter_file="$(mktemp)"
printf '0' >"${counter_file}"
failures_file="$(mktemp)"
printf '0' >"${failures_file}"
trap 'rm -f "${counter_file}" "${failures_file}"' EXIT

# Alternates instances on each call, prints the body and fails the run on any 4xx/5xx.
call() {
  local method="$1" path="$2" body="${3:-}"
  local call_index
  call_index="$(cat "${counter_file}")"
  local base="${instances[$((call_index % 2))]}"
  printf '%s' "$((call_index + 1))" >"${counter_file}"

  local args=(--silent --show-error --write-out '\n%{http_code}' -X "${method}" "${base}${path}")
  if [[ -n "${body}" ]]; then
    args+=(-H 'Content-Type: application/json' --data "${body}")
  fi
  # Double-submit CSRF pair, required on every state-changing request. The token is issued by one
  # instance and presented to another on purpose: being signed and self-contained, it must be
  # accepted without any server-side session (NFG-012).
  if [[ "${method}" != "GET" && "${method}" != "HEAD" && -n "${csrf_token}" ]]; then
    args+=(-H "${CSRF_HEADER_NAME}: ${csrf_token}" -b "${CSRF_COOKIE_NAME}=${csrf_token}")
  fi

  local response status payload
  response="$(curl "${args[@]}")"
  status="${response##*$'\n'}"
  payload="${response%$'\n'*}"

  echo "[${status}] ${method} ${base}${path}" >&2
  if [[ "${status}" -ge 400 ]]; then
    echo "  FAIL: ${payload}" >&2
    printf '%s' "$(( $(cat "${failures_file}") + 1 ))" >"${failures_file}"
  fi
  printf '%s' "${payload}"
}

# Extracts a top-level string field from a JSON payload without requiring jq.
json_field() {
  local payload="$1" field="$2"
  printf '%s' "${payload}" \
    | tr ',{}' '\n' \
    | sed -n "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" \
    | head -n 1
}

echo "== Round-robin journey across ${instances[0]} and ${instances[1]} ==" >&2

csrf_token=""
csrf_token="$(json_field "$(call GET '/api/v1/csrf-token')" token)"
if [[ -z "${csrf_token}" ]]; then
  echo "FAIL: no CSRF token issued; the journey cannot continue." >&2
  exit 1
fi

call GET "/api/v1/products" >/dev/null
call GET "/api/v1/products/${PRODUCT_CODE}" >/dev/null

draft="$(call POST "/api/v1/drafts" "{\"productCode\":\"${PRODUCT_CODE}\"}")"
draft_id="$(json_field "${draft}" draftId)"
if [[ -z "${draft_id}" ]]; then
  echo "FAIL: no draftId returned; the journey cannot continue." >&2
  exit 1
fi
echo "   draftId=${draft_id}" >&2

call PUT "/api/v1/drafts/${draft_id}/subscriber" \
  '{"firstName":"Alice","lastName":"Durand","email":"alice@example.com","birthDate":"1990-01-15"}' \
  >/dev/null
call POST "/api/v1/simulations" \
  "{\"productCode\":\"${PRODUCT_CODE}\",\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\",\"durationYears\":10}" \
  >/dev/null
call PUT "/api/v1/drafts/${draft_id}/simulation" \
  '{"initialAmount":"1000.00","monthlyAmount":"100.00","durationYears":10}' >/dev/null

current="$(call GET "/api/v1/drafts/${draft_id}")"
confirmation_key="$(json_field "${current}" confirmationKey)"

confirmation="$(call POST "/api/v1/drafts/${draft_id}/confirmation" \
  "{\"confirmationKey\":\"${confirmation_key}\"}")"
reference="$(json_field "${confirmation}" reference)"

if [[ -n "${reference}" ]]; then
  call GET "/api/v1/subscriptions/${reference}" >/dev/null
else
  echo "FAIL: no subscription reference returned." >&2
  printf '%s' "$(( $(cat "${failures_file}") + 1 ))" >"${failures_file}"
fi

failures="$(cat "${failures_file}")"
if [[ "${failures}" -ne 0 ]]; then
  echo "RESULT: ${failures} failed call(s) - statelessness NOT verified." >&2
  exit 1
fi

echo "RESULT: journey completed across alternating instances, 0 4xx/5xx (NFG-012 satisfied)." >&2
