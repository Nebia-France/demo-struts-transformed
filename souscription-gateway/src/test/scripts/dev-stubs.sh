#!/usr/bin/env bash
# W6-T05 - flow (a): the gateway alone, in quarkus:dev, against the contract-derived stubs.
#
# Starts one WireMock standalone double per downstream on the IPv4 loopback, serving the mappings
# generated from global/contracts/ (src/test/resources/wiremock/<service>/mappings), then runs
# mvn quarkus:dev wired to them. No sibling service, no container: the WireMock jar is the test
# dependency already resolved in the local Maven repository (copied from Maven Central once if it is
# missing). The doubles are stopped when quarkus:dev exits.
#
# Usage (from the repository root or the module directory):
#   souscription-gateway/src/test/scripts/dev-stubs.sh               # doubles + quarkus:dev
#   souscription-gateway/src/test/scripts/dev-stubs.sh --stubs-only  # doubles only (Ctrl-C to stop)
#
# Ports: gateway 18080 (%dev), management 9000, doubles 18750/18751/18752 (STUB_BASE_PORT).
# Bash 3.2 compatible (macOS default shell).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
WIREMOCK_VERSION="${WIREMOCK_VERSION:-3.13.2}"
WIREMOCK_JAR="${WIREMOCK_JAR:-${HOME}/.m2/repository/org/wiremock/wiremock-standalone/${WIREMOCK_VERSION}/wiremock-standalone-${WIREMOCK_VERSION}.jar}"
FIXTURES="${MODULE_DIR}/src/test/resources/wiremock"
WORK_DIR="${MODULE_DIR}/target/dev-stubs"
STUB_BASE_PORT="${STUB_BASE_PORT:-18750}"

for tool in java mvn curl; do
  command -v "${tool}" >/dev/null 2>&1 || { echo "FAIL: '${tool}' is required." >&2; exit 1; }
done
if [[ ! -f "${WIREMOCK_JAR}" ]]; then
  mvn -q -f "${MODULE_DIR}/pom.xml" dependency:get \
    -Dartifact="org.wiremock:wiremock-standalone:${WIREMOCK_VERSION}" -Dtransitive=false
fi

mkdir -p "${WORK_DIR}"
PIDS=""
cleanup() {
  for pid in ${PIDS}; do kill "${pid}" 2>/dev/null || true; done
}
trap cleanup EXIT INT TERM

start_double() {
  local key="$1" port="$2"
  java -jar "${WIREMOCK_JAR}" --port "${port}" --bind-address 127.0.0.1 \
    --root-dir "${FIXTURES}/${key}" --disable-banner \
    >"${WORK_DIR}/wiremock-${key}.log" 2>&1 &
  PIDS="${PIDS} $!"
  local i
  for i in $(seq 1 100); do
    if curl -s -o /dev/null "http://127.0.0.1:${port}/__admin/mappings"; then
      echo "stub ${key}: http://127.0.0.1:${port}" >&2
      return 0
    fi
    sleep 0.2
  done
  echo "FAIL: WireMock double ${key} did not start on ${port} (see ${WORK_DIR}/wiremock-${key}.log)" >&2
  exit 1
}

start_double catalogue "${STUB_BASE_PORT}"
start_double simulation "$((STUB_BASE_PORT + 1))"
start_double subscription "$((STUB_BASE_PORT + 2))"

export CATALOGUE_BASE_URI="http://127.0.0.1:${STUB_BASE_PORT}"
export SIMULATION_BASE_URI="http://127.0.0.1:$((STUB_BASE_PORT + 1))"
export SUBSCRIPTION_BASE_URI="http://127.0.0.1:$((STUB_BASE_PORT + 2))"
# Signing key generated for this dev session only: never a shared or production secret.
export CSRF_SIGNING_KEY="${CSRF_SIGNING_KEY:-dev-$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')}"
# %dev disables the OpenTelemetry SDK; the reference only has to resolve.
export OTEL_COLLECTOR_ENDPOINT="${OTEL_COLLECTOR_ENDPOINT:-http://127.0.0.1:4317}"

if [[ "${1:-}" == "--stubs-only" ]]; then
  echo "doubles running; export CATALOGUE_BASE_URI=${CATALOGUE_BASE_URI} SIMULATION_BASE_URI=${SIMULATION_BASE_URI} SUBSCRIPTION_BASE_URI=${SUBSCRIPTION_BASE_URI}; Ctrl-C to stop" >&2
  wait
else
  mvn -f "${MODULE_DIR}/pom.xml" quarkus:dev "$@"
fi
