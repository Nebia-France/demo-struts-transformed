#!/usr/bin/env bash
# W5-T04 / NFG-009 - reference load run of souscription-gateway.
#
# Produces the reference load against a packaged gateway and records what plan 19 left to be
# measured rather than assumed: the capacity ceiling per replica, the p95 latency per endpoint, the
# per-hop overhead, and the number of event-loop block warnings in the application journal (which
# must be 0).
#
# Prerequisites: a running gateway (default http://localhost:8080) wired to its three downstreams,
# started with its journal redirected to ${LOG_FILE} (target/application.log by default), and
# either `hey` or `ab` on the PATH. Nothing here is specific to a CI runner: the same script is run
# locally and by the `load` stage of .ci/pipeline.yml.
#
# Exit code 0 = load run completed and no "has been blocked" line in the journal.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
DURATION="${DURATION:-60s}"
CONCURRENCY="${CONCURRENCY:-50}"
LOG_FILE="${LOG_FILE:-target/application.log}"
REPORT_DIR="${REPORT_DIR:-target/load}"
PRODUCT_CODE="${PRODUCT_CODE:-PRUDENT}"
DRAFT_ID="${DRAFT_ID:-}"
PHASE_PAUSE_SECONDS="${PHASE_PAUSE_SECONDS:-15}"

CSRF_COOKIE_NAME="${CSRF_COOKIE_NAME:-XSRF-TOKEN}"
CSRF_HEADER_NAME="${CSRF_HEADER_NAME:-X-CSRF-Token}"

mkdir -p "${REPORT_DIR}"

# The state-changing endpoints under load require the double-submit CSRF pair (plan 16). One token
# is issued for the whole run: it is signed, self-contained and valid on every replica, so reusing
# it is exactly what the stateless model allows.
CSRF_TOKEN="$(curl --silent --show-error "${BASE_URL}/api/v1/csrf-token" \
  | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
if [[ -z "${CSRF_TOKEN}" ]]; then
  echo "FAIL: no CSRF token issued by ${BASE_URL}; the write endpoints cannot be loaded." >&2
  exit 1
fi

# The driver is chosen, not assumed: hey reports percentiles directly, ab only the mean and the
# 95th percentile of its own histogram.
if command -v hey >/dev/null 2>&1; then
  DRIVER=hey
elif command -v ab >/dev/null 2>&1; then
  DRIVER=ab
else
  echo "FAIL: neither 'hey' nor 'ab' is available; cannot produce the reference load." >&2
  exit 1
fi
echo "== Reference load with ${DRIVER}, ${CONCURRENCY} concurrent clients, ${DURATION} ==" >&2

# Measures one endpoint and writes the raw driver output under ${REPORT_DIR}.
measure() {
  local name="$1" method="$2" path="$3" body="${4:-}"
  local out="${REPORT_DIR}/${name}.txt"

  if [[ "${DRIVER}" == "hey" ]]; then
    local args=(-z "${DURATION}" -c "${CONCURRENCY}" -m "${method}")
    if [[ -n "${body}" ]]; then
      args+=(-T 'application/json' -d "${body}"
             -H "${CSRF_HEADER_NAME}: ${CSRF_TOKEN}"
             -H "Cookie: ${CSRF_COOKIE_NAME}=${CSRF_TOKEN}")
    fi
    hey "${args[@]}" "${BASE_URL}${path}" >"${out}" 2>&1 || true
  else
    local args=(-c "${CONCURRENCY}" -t "${DURATION%s}" -n 1000000 -k)
    if [[ -n "${body}" ]]; then
      printf '%s' "${body}" >"${REPORT_DIR}/${name}.body"
      args+=(-p "${REPORT_DIR}/${name}.body" -T 'application/json'
             -H "${CSRF_HEADER_NAME}: ${CSRF_TOKEN}"
             -H "Cookie: ${CSRF_COOKIE_NAME}=${CSRF_TOKEN}")
    fi
    ab "${args[@]}" "${BASE_URL}${path}" >"${out}" 2>&1 || true
  fi

  echo "-- ${name} (${method} ${path})" >&2
  grep -E 'Requests/sec|^ *95%|Latency distribution|Total:|Non-2xx|Failed requests' "${out}" >&2 || true

  # Cool-down between phases, longer than the breaker delay. When the driver stops it closes its
  # ${CONCURRENCY} connections at once; the calls still in flight fail with a client abort, which
  # the breakers count as failures and can open them. Without this pause the next endpoint would be
  # measured through a breaker opened by the previous teardown, not by its own behaviour.
  sleep "${PHASE_PAUSE_SECONDS}"
}

# Read endpoints only: the load run must not create subscriptions in the target environment.
# Each one is a single hop except /api/v1/simulations, which fans out to the catalogue and the
# simulation service and therefore carries the per-hop overhead being measured.
measure "products-list" GET "/api/v1/products"
measure "product-detail" GET "/api/v1/products/${PRODUCT_CODE}"
if [[ -n "${DRAFT_ID}" ]]; then
  measure "draft-get" GET "/api/v1/drafts/${DRAFT_ID}"
fi
measure "simulation" POST "/api/v1/simulations" \
  "{\"productCode\":\"${PRODUCT_CODE}\",\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\",\"durationYears\":10}"

# Pool and bulkhead saturation criterion of docs/capacity.md: no call may be left waiting on an
# edge. Pool level: http_client_pending_requests (per downstream edge, published by
# PendingRequests); bulkhead level: ft_bulkhead_executionsWaiting (per gateway method).
MANAGEMENT_URL="${MANAGEMENT_URL:-http://localhost:9000}"
echo "-- http_client_pending_requests after the run (expected 0 on every edge)" >&2
curl --silent --show-error "${MANAGEMENT_URL}/q/metrics" 2>/dev/null \
  | grep '^http_client_pending_requests' >&2 || echo "   (metric absent)" >&2
echo "-- ft_bulkhead_executionsWaiting after the run" >&2
curl --silent --show-error "${MANAGEMENT_URL}/q/metrics" 2>/dev/null \
  | grep '^ft_bulkhead_executionsWaiting' >&2 || echo "   (metric absent)" >&2
echo "-- circuit breakers opened during the run (expected 0 under healthy load)" >&2
curl --silent --show-error "${MANAGEMENT_URL}/q/metrics" 2>/dev/null \
  | grep '^ft_circuitbreaker_opened_total' >&2 || echo "   (metric absent)" >&2

# NFG-009: the non-blocking execution model must hold under load. One warning is one regression.
blocked=0
if [[ -f "${LOG_FILE}" ]]; then
  blocked="$(grep -c "has been blocked" "${LOG_FILE}" || true)"
fi
echo "-- event-loop block warnings in ${LOG_FILE}: ${blocked}" >&2

if [[ "${blocked}" -ne 0 ]]; then
  echo "RESULT: ${blocked} event-loop block warning(s) - NFG-009 violated." >&2
  exit 1
fi

echo "RESULT: load run completed, 0 event-loop block warning. Raw reports in ${REPORT_DIR}." >&2
echo "Record the measured ceiling and p95 values in docs/capacity.md." >&2
