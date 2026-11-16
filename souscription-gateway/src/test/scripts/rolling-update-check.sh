#!/usr/bin/env bash
# W6-T01 - graceful drain and rolling update check of souscription-gateway.
#
# Two checks against the packaged gateway (target/quarkus-app/quarkus-run.jar), wired to WireMock
# standalone doubles served directly from the contract fixtures under src/test/resources/wiremock/:
#
#   1. Drain under sustained load. Load workers keep calling the instance; IN_FLIGHT long requests
#      (downstream delay DRAIN_DELAY_MS) are in flight when SIGTERM is sent. Every request accepted
#      before SIGTERM must complete with 200 (0 cut request) and the process must exit within
#      DRAIN_BUDGET_SECONDS (quarkus.shutdown.timeout = 10 s).
#   2. Rolling update. Load workers round-robin over an endpoint list, as the Service does. Each of
#      the two replicas is replaced one by one exactly as the Deployment does it (maxSurge 1,
#      maxUnavailable 0): start the new replica, wait for :mgmt/q/health/ready, add it to the
#      endpoints, remove the old one from the endpoints, wait PRESTOP_SECONDS (the preStop sleep of
#      deploy/deployment.yaml), SIGTERM, wait for exit. 0 failed request is expected.
#
# Also measured: startup-to-readiness of each started replica, and resting RSS of an idle replica.
# The results are printed on stderr; record them in docs/operational-cost.md.
#
# Prerequisites: mvn -f souscription-gateway/pom.xml package, java 21, curl, perl. Run from the
# module directory (souscription-gateway/). Bash 3.2 compatible (macOS default shell).
#
# Exit code 0 = 0 cut request during the drain, drain within budget, 0 failed request during the
# rolling update.

set -euo pipefail

RUN_JAR="${RUN_JAR:-target/quarkus-app/quarkus-run.jar}"
WIREMOCK_VERSION="${WIREMOCK_VERSION:-3.13.2}"
WIREMOCK_JAR="${WIREMOCK_JAR:-${HOME}/.m2/repository/org/wiremock/wiremock-standalone/${WIREMOCK_VERSION}/wiremock-standalone-${WIREMOCK_VERSION}.jar}"
FIXTURES="${FIXTURES:-src/test/resources/wiremock}"
WORK_DIR="${WORK_DIR:-target/rolling-update}"
BASE_PORT="${BASE_PORT:-18700}"
WORKERS="${WORKERS:-8}"
# IN_FLIGHT + WORKERS must stay <= 20, the catalogue bulkhead: beyond it calls queue in the
# bulkhead and never hold a connection, so they cannot be observed in flight.
IN_FLIGHT="${IN_FLIGHT:-10}"
DRAIN_DELAY_MS="${DRAIN_DELAY_MS:-2000}"
DRAIN_BUDGET_SECONDS="${DRAIN_BUDGET_SECONDS:-10}"
ROLLOUT_DELAY_MS="${ROLLOUT_DELAY_MS:-50}"
PRESTOP_SECONDS="${PRESTOP_SECONDS:-2}"
LOAD_WARMUP_SECONDS="${LOAD_WARMUP_SECONDS:-3}"
RSS_SETTLE_SECONDS="${RSS_SETTLE_SECONDS:-30}"
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-60}"
PATH_UNDER_LOAD="${PATH_UNDER_LOAD:-/api/v1/products}"

# Test-only signing key generated for this run: never a production secret.
export CSRF_SIGNING_KEY="${CSRF_SIGNING_KEY:-rolling-update-check-$-$(date +%s)}"
# The packaged gateway requires a collector endpoint (W6-T04). This check asserts on the drain,
# not on traces: tracing is off unless the caller provides a collector.
if [[ -z "${OTEL_COLLECTOR_ENDPOINT:-}" ]]; then
  export OTEL_COLLECTOR_ENDPOINT="http://127.0.0.1:4317"
  export QUARKUS_OTEL_SDK_DISABLED="${QUARKUS_OTEL_SDK_DISABLED:-true}"
fi

for tool in java curl perl; do
  command -v "${tool}" >/dev/null 2>&1 || { echo "FAIL: '${tool}' is required." >&2; exit 1; }
done
[[ -f "${RUN_JAR}" ]] || { echo "FAIL: ${RUN_JAR} missing - run mvn -f souscription-gateway/pom.xml package" >&2; exit 1; }
[[ -f "${WIREMOCK_JAR}" ]] || { echo "FAIL: WireMock standalone jar missing at ${WIREMOCK_JAR}" >&2; exit 1; }

mkdir -p "${WORK_DIR}"
rm -f "${WORK_DIR}"/worker-*.log "${WORK_DIR}"/inflight-*.log "${WORK_DIR}"/stop "${WORK_DIR}"/endpoints

PIDS_FILE="${WORK_DIR}/pids"
: >"${PIDS_FILE}"
cleanup() {
  touch "${WORK_DIR}/stop"
  while read -r pid; do
    kill "${pid}" 2>/dev/null || true
  done <"${PIDS_FILE}"
}
trap cleanup EXIT

now_ms() { perl -MTime::HiRes=time -e 'printf("%d\n", time()*1000)'; }

# ------------------------------------------------------------------ downstream doubles

CATALOGUE_PORT=$((BASE_PORT + 50))
SIMULATION_PORT=$((BASE_PORT + 51))
SUBSCRIPTION_PORT=$((BASE_PORT + 52))

start_double() {
  local key="$1" port="$2"
  java -jar "${WIREMOCK_JAR}" --port "${port}" --bind-address 127.0.0.1 \
    --root-dir "${FIXTURES}/${key}" --disable-banner --no-request-journal \
    >"${WORK_DIR}/wiremock-${key}.log" 2>&1 &
  echo $! >>"${PIDS_FILE}"
  local i
  for i in $(seq 1 100); do
    if curl -s -o /dev/null "http://127.0.0.1:${port}/__admin/mappings"; then return 0; fi
    sleep 0.2
  done
  echo "FAIL: WireMock double ${key} did not start on ${port}" >&2
  exit 1
}

catalogue_delay() {
  curl -s -o /dev/null -X POST -H 'Content-Type: application/json' \
    --data "{\"fixedDelay\": $1}" "http://127.0.0.1:${CATALOGUE_PORT}/__admin/settings"
}

start_double catalogue "${CATALOGUE_PORT}"
start_double simulation "${SIMULATION_PORT}"
start_double subscription "${SUBSCRIPTION_PORT}"

# ------------------------------------------------------------------ gateway replicas

# Starts one replica on $1 (management on $1 + 1000), prints its PID; startup-to-readiness (ms) is
# appended to ${WORK_DIR}/readiness.
start_gateway() {
  local port="$1" mgmt=$(( $1 + 1000 ))
  local started
  started="$(now_ms)"
  # Non-prod profile: the %prod outbound mTLS material is a cluster secret (docs/mtls.md).
  java -XX:MaxRAMPercentage=70 -Dquarkus.profile=rolling \
    -Dquarkus.http.port="${port}" -Dquarkus.management.port="${mgmt}" \
    -Dquarkus.rest-client.catalogue.url="http://127.0.0.1:${CATALOGUE_PORT}" \
    -Dquarkus.rest-client.simulation.url="http://127.0.0.1:${SIMULATION_PORT}" \
    -Dquarkus.rest-client.subscription.url="http://127.0.0.1:${SUBSCRIPTION_PORT}" \
    -jar "${RUN_JAR}" >"${WORK_DIR}/gateway-${port}.log" 2>&1 &
  local pid=$!
  echo "${pid}" >>"${PIDS_FILE}"
  local deadline=$(( started + READY_TIMEOUT_SECONDS * 1000 ))
  while [[ "$(now_ms)" -lt "${deadline}" ]]; do
    if curl -sf -o /dev/null "http://127.0.0.1:${mgmt}/q/health/ready"; then
      echo "${port} $(( $(now_ms) - started ))" >>"${WORK_DIR}/readiness"
      echo "${pid}"
      return 0
    fi
    sleep 0.05
  done
  echo "FAIL: replica ${port} not ready after ${READY_TIMEOUT_SECONDS}s (see ${WORK_DIR}/gateway-${port}.log)" >&2
  exit 1
}

# SIGTERM then wait for exit; prints the drain duration in ms.
stop_gateway() {
  local pid="$1" sigterm
  sigterm="$(now_ms)"
  kill -TERM "${pid}"
  while kill -0 "${pid}" 2>/dev/null; do sleep 0.05; done
  echo $(( $(now_ms) - sigterm ))
}

# ------------------------------------------------------------------ load workers

# One worker: round-robin over the ports listed in ${WORK_DIR}/endpoints until the stop flag.
# Each line: start_ms http_code curl_exit port.
worker() {
  local id="$1"
  local n=0
  local out="${WORK_DIR}/worker-${id}.log"
  while [[ ! -f "${WORK_DIR}/stop" ]]; do
    local ports port count
    ports="$(cat "${WORK_DIR}/endpoints" 2>/dev/null || true)"
    count="$(printf '%s\n' ${ports} | grep -c . || true)"
    if [[ "${count}" -eq 0 ]]; then sleep 0.05; continue; fi
    port="$(printf '%s\n' ${ports} | sed -n "$(( (n + id) % count + 1 ))p")"
    n=$(( n + 1 ))
    local start code rc=0
    start="$(now_ms)"
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "http://127.0.0.1:${port}${PATH_UNDER_LOAD}")" || rc=$?
    echo "${start} ${code} ${rc} ${port}" >>"${out}"
  done
}

start_workers() {
  rm -f "${WORK_DIR}/stop"
  local i
  for i in $(seq 1 "${WORKERS}"); do
    worker "${i}" &
    echo $! >>"${PIDS_FILE}"
  done
}

stop_workers() {
  touch "${WORK_DIR}/stop"
  sleep 1
}

# Curl exit codes meaning the connection was established and then cut: 18 partial transfer,
# 52 empty reply, 55 send failure, 56 receive failure.
is_cut() { case "$1" in 18|52|55|56) return 0 ;; *) return 1 ;; esac; }

: >"${WORK_DIR}/readiness"
status=0

# ================================================================== 1. drain under sustained load

echo "== 1. Drain under sustained load (${WORKERS} workers, ${IN_FLIGHT} long requests of ${DRAIN_DELAY_MS} ms) ==" >&2
DRAIN_PORT="${BASE_PORT}"
drain_pid="$(start_gateway "${DRAIN_PORT}")"

# Resting RSS of an idle replica, before any traffic.
rss_ready_kb="$(ps -o rss= -p "${drain_pid}" | tr -d ' ')"
sleep "${RSS_SETTLE_SECONDS}"
rss_kb="$(ps -o rss= -p "${drain_pid}" | tr -d ' ')"

echo "${DRAIN_PORT}" >"${WORK_DIR}/endpoints"
start_workers
sleep "${LOAD_WARMUP_SECONDS}"

catalogue_delay "${DRAIN_DELAY_MS}"
for i in $(seq 1 "${IN_FLIGHT}"); do
  (
    start="$(now_ms)"; rc=0
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "http://127.0.0.1:${DRAIN_PORT}${PATH_UNDER_LOAD}")" || rc=$?
    echo "${start} ${code} ${rc}" >"${WORK_DIR}/inflight-${i}.log"
  ) &
done
# SIGTERM only once every long request holds a downstream connection: the pool-level gauge
# published by PendingRequests counts them, the load workers' requests included.
inflight_deadline=$(( $(now_ms) + 10000 ))
while :; do
  pending="$(curl -s "http://127.0.0.1:$(( DRAIN_PORT + 1000 ))/q/metrics" \
    | sed -n 's/^http_client_pending_requests{clientName="catalogue"[^}]*} \([0-9.]*\).*/\1/p' | head -n 1)"
  pending="${pending%%.*}"
  if [[ "${pending:-0}" -ge $(( IN_FLIGHT + WORKERS )) ]]; then break; fi
  if [[ "$(now_ms)" -gt "${inflight_deadline}" ]]; then
    echo "FAIL: the ${IN_FLIGHT} long requests never reached the catalogue edge (pending=${pending:-none})" >&2
    exit 1
  fi
  sleep 0.05
done
echo "   pending catalogue requests at SIGTERM: ${pending}" >&2
sigterm_ms="$(now_ms)"
drain_ms="$(stop_gateway "${drain_pid}")"
wait_deadline=$(( $(now_ms) + 30000 ))
while [[ "$(ls "${WORK_DIR}"/inflight-*.log 2>/dev/null | wc -l | tr -d ' ')" -lt "${IN_FLIGHT}" \
        && "$(now_ms)" -lt "${wait_deadline}" ]]; do
  sleep 0.1
done
stop_workers
catalogue_delay 0

inflight_ok=0
inflight_cut=0
for f in "${WORK_DIR}"/inflight-*.log; do
  read -r _ code rc <"${f}"
  if [[ "${code}" == "200" ]]; then inflight_ok=$(( inflight_ok + 1 )); else inflight_cut=$(( inflight_cut + 1 )); fi
done
inflight_missing=$(( IN_FLIGHT - inflight_ok - inflight_cut ))

# Load requests started before SIGTERM must have completed with 200; requests sent after SIGTERM
# are refused cleanly (503 from the shutdown filter, or connection refused once the port closed):
# in a cluster none arrive, the preStop sleep having removed the pod from the endpoints.
load_before=0; load_before_failed=0; load_cut=0
load_after=0; load_after_served=0; load_after_503=0; load_after_refused=0; load_after_cut=0
for f in "${WORK_DIR}"/worker-*.log; do
  while read -r start code rc _; do
    if [[ "${start}" -lt "${sigterm_ms}" ]]; then
      load_before=$(( load_before + 1 ))
      [[ "${code}" == "200" ]] || load_before_failed=$(( load_before_failed + 1 ))
      if is_cut "${rc}"; then load_cut=$(( load_cut + 1 )); fi
    else
      load_after=$(( load_after + 1 ))
      if [[ "${code}" == "200" ]]; then load_after_served=$(( load_after_served + 1 ))
      elif [[ "${code}" == "503" ]]; then load_after_503=$(( load_after_503 + 1 ))
      elif is_cut "${rc}"; then load_after_cut=$(( load_after_cut + 1 ))
      else load_after_refused=$(( load_after_refused + 1 )); fi
    fi
  done <"${f}"
done
drain_completed="$(grep -c 'All HTTP requests complete' "${WORK_DIR}/gateway-${DRAIN_PORT}.log" || true)"

echo "   long requests in flight at SIGTERM: ${inflight_ok}/${IN_FLIGHT} completed with 200, ${inflight_cut} failed, ${inflight_missing} missing" >&2
echo "   load requests started before SIGTERM: ${load_before}, failed: ${load_before_failed}" >&2
echo "   cut connections among them (curl 18/52/55/56): ${load_cut}" >&2
echo "   load requests sent after SIGTERM (no endpoint removal locally; none arrive in a cluster): ${load_after}" \
     "- served ${load_after_served}, 503 ${load_after_503}, connection refused ${load_after_refused}, cut ${load_after_cut}" >&2
echo "   SIGTERM to exit: ${drain_ms} ms (budget ${DRAIN_BUDGET_SECONDS} s); drain logged: ${drain_completed}" >&2
if [[ "${inflight_ok}" -ne "${IN_FLIGHT}" || "${load_before_failed}" -ne 0 || "${load_cut}" -ne 0 \
      || "${drain_ms}" -gt $(( DRAIN_BUDGET_SECONDS * 1000 )) || "${drain_completed}" -eq 0 ]]; then
  echo "   FAIL: drain check" >&2
  status=1
fi

# ================================================================== 2. rolling update

echo "== 2. Rolling update of 2 replicas under load (${WORKERS} workers, preStop ${PRESTOP_SECONDS} s) ==" >&2
rm -f "${WORK_DIR}"/worker-*.log
catalogue_delay "${ROLLOUT_DELAY_MS}"
P1=$(( BASE_PORT + 1 )); P2=$(( BASE_PORT + 2 )); P3=$(( BASE_PORT + 3 )); P4=$(( BASE_PORT + 4 ))
pid1="$(start_gateway "${P1}")"
pid2="$(start_gateway "${P2}")"
printf '%s\n%s\n' "${P1}" "${P2}" >"${WORK_DIR}/endpoints"
start_workers
sleep "${LOAD_WARMUP_SECONDS}"

rollout_drains=""
replace() {
  local old_port="$1" old_pid="$2" new_port="$3"
  local new_pid
  new_pid="$(start_gateway "${new_port}")"
  # maxSurge 1: the new replica joins the endpoints once ready ...
  { grep -v "^${old_port}\$" "${WORK_DIR}/endpoints"; echo "${new_port}"; } >"${WORK_DIR}/endpoints.next"
  # ... and the old one leaves them; requests still routed to it during the preStop sleep are served.
  mv "${WORK_DIR}/endpoints.next" "${WORK_DIR}/endpoints"
  sleep "${PRESTOP_SECONDS}"
  rollout_drains="${rollout_drains} $(stop_gateway "${old_pid}")ms"
  sleep "${LOAD_WARMUP_SECONDS}"
}
replace "${P1}" "${pid1}" "${P3}"
replace "${P2}" "${pid2}" "${P4}"
stop_workers
catalogue_delay 0

rollout_total=0; rollout_failed=0
for f in "${WORK_DIR}"/worker-*.log; do
  while read -r _ code rc _; do
    rollout_total=$(( rollout_total + 1 ))
    [[ "${code}" == "200" && "${rc}" == "0" ]] || rollout_failed=$(( rollout_failed + 1 ))
  done <"${f}"
done
echo "   requests during the rollout: ${rollout_total}, failed: ${rollout_failed}; drains:${rollout_drains}" >&2
if [[ "${rollout_total}" -eq 0 || "${rollout_failed}" -ne 0 ]]; then
  echo "   FAIL: rolling update check" >&2
  status=1
fi

# ================================================================== measurements

echo "== Measurements ==" >&2
while read -r port ms; do
  echo "   startup-to-readiness replica ${port}: ${ms} ms (Quarkus: $(grep -o 'started in [0-9.]*s' "${WORK_DIR}/gateway-${port}.log" | head -n 1))" >&2
done <"${WORK_DIR}/readiness"
echo "   RSS at readiness: $(( rss_ready_kb / 1024 )) MB; resting RSS after ${RSS_SETTLE_SECONDS} s idle: $(( rss_kb / 1024 )) MB (${rss_kb} kB)" >&2

if [[ "${status}" -eq 0 ]]; then
  echo "RESULT: 0 cut request during the drain (${drain_ms} ms), 0 failed request during the rolling update." >&2
else
  echo "RESULT: FAIL - see above and ${WORK_DIR}/." >&2
fi
exit "${status}"
