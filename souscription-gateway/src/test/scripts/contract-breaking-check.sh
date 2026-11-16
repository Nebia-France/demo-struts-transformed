#!/usr/bin/env bash
# W7-T03 - breaking-change detector for the three contracts consumed by souscription-gateway.
#
# Demonstrates that a breaking change in a downstream /v1 contract fails the gateway build instead
# of failing in production. On a scratch copy of the module and of global/contracts/ it:
#   1. applies a breaking mutation to a downstream spec: the required member `annualRate` of
#      shared-schemas/SimulationComputeRequest.json (request of POST /v1/simulations, consumed
#      through souscription-gateway-to-souscription-simulation-service) is renamed
#      `annualInterestRate`;
#   2. regenerates the WireMock stubs of the copy from the mutated contracts (generate-stubs.sh),
#      exactly as a developer syncing the new provider spec would;
#   3. runs the gateway contract tests of the copy (ProvidedContractTest, ConsumedContractTest,
#      ContractErrorMappingTest), or the whole build with --full;
#   4. exits 0 only if that build FAILS and the failure is reported by ConsumedContractTest on the
#      renamed member. A green build, or a red build for an unrelated reason, exits 1.
#
# The working tree is never modified: the copy lives under souscription-gateway/target/ and is
# removed by `mvn clean`.
#
# Usage:
#   bash souscription-gateway/src/test/scripts/contract-breaking-check.sh [--full]
# Environment: MVN (default mvn), MVN_ARGS (extra Maven arguments, e.g. -o).
# Requirements: bash, rsync, python3 with PyYAML, Maven, JDK 21.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
ROOT_DIR="$(cd "${MODULE_DIR}/.." && pwd)"
MVN="${MVN:-mvn}"
MVN_ARGS="${MVN_ARGS:-}"
CONTRACT_TESTS="ProvidedContractTest,ConsumedContractTest,ContractErrorMappingTest"
SCHEMA="shared-schemas/SimulationComputeRequest.json"
FROM_MEMBER="annualRate"
TO_MEMBER="annualInterestRate"

FULL=false
if [[ "${1:-}" == "--full" ]]; then
  FULL=true
elif [[ -n "${1:-}" ]]; then
  echo "usage: $0 [--full]" >&2
  exit 2
fi

for tool in rsync python3 "${MVN}"; do
  command -v "${tool}" >/dev/null 2>&1 || { echo "FAIL: ${tool} is required." >&2; exit 1; }
done

mkdir -p "${MODULE_DIR}/target"
WORK="$(mktemp -d "${MODULE_DIR}/target/contract-breaking-check.XXXXXX")"
COPY="${WORK}/souscription-gateway"
echo "== scratch copy: ${WORK}"
rsync -a --exclude 'target/' "${MODULE_DIR}/" "${COPY}/"
mkdir -p "${WORK}/global"
rsync -a "${ROOT_DIR}/global/contracts/" "${WORK}/global/contracts/"

echo "== 1. breaking mutation: ${SCHEMA} required '${FROM_MEMBER}' -> '${TO_MEMBER}'"
python3 - "${WORK}/global/contracts" "${SCHEMA}" "${FROM_MEMBER}" "${TO_MEMBER}" <<'PYTHON'
import json, os, sys
contracts, schema, old, new = sys.argv[1:5]
path = os.path.join(contracts, schema)
with open(path, encoding="utf-8") as handle:
    document = json.load(handle)
if old not in document.get("required", []) or old not in document.get("properties", {}):
    raise SystemExit(f"FAIL: {schema} no longer declares the required member {old}; update the script")
document["required"] = [new if member == old else member for member in document["required"]]
document["properties"] = {(new if name == old else name): value
                          for name, value in document["properties"].items()}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(document, handle, indent=2, ensure_ascii=False)
    handle.write("\n")
consumers = [name for name in sorted(os.listdir(contracts))
             if name.startswith("souscription-gateway-to-")
             and os.path.basename(schema) in open(os.path.join(contracts, name, "openapi.yaml"),
                                                  encoding="utf-8").read()]
if consumers != ["souscription-gateway-to-souscription-simulation-service"]:
    raise SystemExit(f"FAIL: the mutated schema is expected to reach the gateway through the"
                     f" simulation contract only, found {consumers}")
print(f"   mutated {schema}; consumed by the gateway through {consumers[0]}")
PYTHON

echo "== 2. regenerate the stubs of the copy from the mutated contracts"
bash "${COPY}/src/test/scripts/generate-stubs.sh"

if [[ "${FULL}" == true ]]; then
  GOALS=(clean verify)
else
  GOALS=(test "-Dtest=${CONTRACT_TESTS}" -Dsurefire.failIfNoSpecifiedTests=false)
fi
echo "== 3. gateway build of the copy: ${MVN} ${GOALS[*]}"
set +e
# shellcheck disable=SC2086
"${MVN}" -B ${MVN_ARGS} -f "${COPY}/pom.xml" "${GOALS[@]}" > "${WORK}/build.log" 2>&1
STATUS=$?
set -e
echo "   build exit status: ${STATUS} (log: ${WORK}/build.log)"

echo "== 4. verdict"
if [[ ${STATUS} -eq 0 ]]; then
  echo "FAIL: the gateway build PASSED against a breaking downstream change - not detected." >&2
  exit 1
fi
REPORT="${COPY}/target/surefire-reports/TEST-fr.demo.souscription.gateway.contract.ConsumedContractTest.xml"
if [[ ! -f "${REPORT}" ]]; then
  echo "FAIL: the build failed before ConsumedContractTest ran - unrelated failure:" >&2
  tail -n 40 "${WORK}/build.log" >&2
  exit 1
fi
FAILURES="$(grep -c '<failure\|<error' "${REPORT}" || true)"
if [[ "${FAILURES}" -eq 0 ]] || ! grep -q "${TO_MEMBER}" "${REPORT}"; then
  echo "FAIL: the build failed, but not on the renamed contract member - unrelated failure:" >&2
  tail -n 40 "${WORK}/build.log" >&2
  exit 1
fi
echo "   ConsumedContractTest reported ${FAILURES} failing case(s), naming '${TO_MEMBER}':"
grep -o 'message="[^"]*"' "${REPORT}" | head -n 5 | sed 's/^/     /'
echo "OK: the breaking downstream change makes the gateway build fail."
exit 0
