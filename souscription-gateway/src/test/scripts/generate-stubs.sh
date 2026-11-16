#!/usr/bin/env bash
# W6-T05 - derive the WireMock doubles of the three downstreams from their OpenAPI contracts.
#
# Reads global/contracts/souscription-gateway-to-souscription-{catalogue-service,simulation-service,
# service}/openapi.yaml (and the shared JSON schemas they reference) plus the example values of
# src/test/scripts/stub-fixtures.yaml, and regenerates src/test/resources/wiremock/<service>/mappings/.
# Method, base path, path template, declared parameters, status codes, media types, response headers,
# required request members and problem bodies all come from the contract; every example is
# validated against the contract schema and the generation fails on the first mismatch.
#
# Usage (from anywhere):
#   souscription-gateway/src/test/scripts/generate-stubs.sh          # regenerate in place
#   souscription-gateway/src/test/scripts/generate-stubs.sh --check  # exit 1 if a stub drifted
#
# The generated files are committed: re-running the generator must leave
# src/test/resources/wiremock/ unchanged (git diff --exit-code), which is the precondition of the
# consumer contract test. Requirements: python3 with PyYAML (pip install pyyaml). No network.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
CONTRACTS_DIR="${CONTRACTS_DIR:-${MODULE_DIR}/../global/contracts}"
FIXTURES_FILE="${FIXTURES_FILE:-${SCRIPT_DIR}/stub-fixtures.yaml}"
OUTPUT_DIR="${OUTPUT_DIR:-${MODULE_DIR}/src/test/resources/wiremock}"
MODE="write"
if [[ "${1:-}" == "--check" ]]; then
  MODE="check"
elif [[ -n "${1:-}" ]]; then
  echo "usage: $0 [--check]" >&2
  exit 2
fi

command -v python3 >/dev/null 2>&1 || { echo "FAIL: python3 is required." >&2; exit 1; }
python3 -c 'import yaml' 2>/dev/null || { echo "FAIL: PyYAML is required (pip install pyyaml)." >&2; exit 1; }
[[ -d "${CONTRACTS_DIR}" ]] || { echo "FAIL: contracts not found at ${CONTRACTS_DIR}" >&2; exit 1; }

python3 - "${CONTRACTS_DIR}" "${FIXTURES_FILE}" "${OUTPUT_DIR}" "${MODE}" <<'PYTHON'
import http
import json
import os
import re
import sys
from urllib.parse import urlparse

import yaml

contracts_dir, fixtures_file, output_dir, mode = sys.argv[1:5]
GENERATOR = "src/test/scripts/generate-stubs.sh"


class StubError(Exception):
    pass


_documents = {}


def load(path):
    path = os.path.normpath(path)
    if path not in _documents:
        with open(path, encoding="utf-8") as handle:
            _documents[path] = yaml.safe_load(handle) if path.endswith((".yaml", ".yml")) else json.load(handle)
    return _documents[path]


def walk(path, pointer):
    """Follows a JSON pointer, dereferencing intermediate $ref nodes. Returns (file, node)."""
    node = load(path)
    for token in [t for t in pointer.lstrip("/").split("/") if t]:
        path, node = deref(path, node)
        token = token.replace("~1", "/").replace("~0", "~")
        if not isinstance(node, dict) or token not in node:
            raise StubError(f"unresolvable pointer {pointer} in {path}")
        node = node[token]
    return path, node


def deref(path, node):
    while isinstance(node, dict) and "$ref" in node:
        ref = node["$ref"]
        target, _, pointer = ref.partition("#")
        path, node = walk(os.path.join(os.path.dirname(path), target) if target else path, pointer)
    return path, node


FORMATS = {
    "uuid": r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    "date": r"^\d{4}-\d{2}-\d{2}$",
    "date-time": r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$",
}


def type_of(value):
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, int):
        return "integer"
    if isinstance(value, float):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    return "object"


def validate(path, schema, value, where):
    path, schema = deref(path, schema)
    if "oneOf" in schema:
        matches = 0
        for option in schema["oneOf"]:
            try:
                validate(path, option, value, where)
                matches += 1
            except StubError:
                pass
        if matches != 1:
            raise StubError(f"{where}: matches {matches} branches of oneOf, expected exactly 1")
        return
    expected = schema.get("type")
    actual = type_of(value)
    if expected and actual != expected and not (expected == "number" and actual == "integer"):
        raise StubError(f"{where}: {actual} where the contract requires {expected}")
    if "enum" in schema and value not in schema["enum"]:
        raise StubError(f"{where}: {value!r} not in {schema['enum']}")
    if actual == "string":
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            raise StubError(f"{where}: longer than {schema['maxLength']}")
        if "minLength" in schema and len(value) < schema["minLength"]:
            raise StubError(f"{where}: shorter than {schema['minLength']}")
        if "pattern" in schema and not re.search(schema["pattern"], value):
            raise StubError(f"{where}: {value!r} does not match {schema['pattern']}")
        if schema.get("format") in FORMATS and not re.match(FORMATS[schema["format"]], value):
            raise StubError(f"{where}: {value!r} is not a {schema['format']}")
    if actual in ("integer", "number"):
        if "minimum" in schema and value < schema["minimum"]:
            raise StubError(f"{where}: below {schema['minimum']}")
        if "maximum" in schema and value > schema["maximum"]:
            raise StubError(f"{where}: above {schema['maximum']}")
    if actual == "object":
        properties = schema.get("properties", {})
        for member in schema.get("required", []):
            if member not in value:
                raise StubError(f"{where}: required member '{member}' missing")
        for member, member_value in value.items():
            if member in properties:
                validate(path, properties[member], member_value, f"{where}.{member}")
            elif schema.get("additionalProperties") is False:
                raise StubError(f"{where}: member '{member}' not allowed by the contract")
    if actual == "array" and "items" in schema:
        for index, item in enumerate(value):
            validate(path, schema["items"], item, f"{where}[{index}]")


class Contract:
    def __init__(self, name):
        self.name = name
        self.file = os.path.join(contracts_dir, name, "openapi.yaml")
        self.doc = load(self.file)
        self.base = urlparse(self.doc["servers"][0]["url"]).path.rstrip("/")
        self.operations = {}
        for template, item in self.doc["paths"].items():
            shared = item.get("parameters", [])
            for method, operation in item.items():
                if method in ("get", "put", "post", "delete", "patch", "head", "options"):
                    parameters = {}
                    for parameter in shared + operation.get("parameters", []):
                        _, resolved = deref(self.file, parameter)
                        parameters[(resolved["in"], resolved["name"].lower())] = resolved
                    self.operations[operation["operationId"]] = {
                        "method": method.upper(), "template": template,
                        "operation": operation, "parameters": parameters,
                    }

    def op(self, operation_id):
        if operation_id not in self.operations:
            raise StubError(f"operation {operation_id} is not published by {self.name}")
        return self.operations[operation_id]

    def response(self, operation_id, status):
        responses = self.op(operation_id)["operation"]["responses"]
        if str(status) not in responses:
            raise StubError(f"{operation_id} declares no {status} response in {self.name}")
        return deref(self.file, responses[str(status)])[1]

    def parameter_schema(self, operation_id, location, name):
        parameters = self.op(operation_id)["parameters"]
        if (location, name.lower()) not in parameters:
            raise StubError(f"{operation_id} declares no {location} parameter '{name}'")
        return parameters[(location, name.lower())]


def substitute(template, values, escape):
    def replace(match):
        if match.group(1) not in values:
            raise StubError(f"path parameter '{match.group(1)}' of {template} has no value")
        return values[match.group(1)]
    parts = re.split(r"(\{[^}]+\})", template)
    return "".join(replace(re.match(r"\{([^}]+)\}", p)) if p.startswith("{") else (re.escape(p) if escape else p)
                   for p in parts)


def url_matcher(contract, operation_ids, stub):
    templates = [contract.op(o)["template"] for o in operation_ids]
    if "pathParams" in stub:
        if len(templates) != 1:
            raise StubError("pathParams needs a single operation")
        for name, value in stub["pathParams"].items():
            validate(contract.file, contract.parameter_schema(operation_ids[0], "path", name)["schema"],
                     value, f"path parameter {name}")
        return "urlPath", contract.base + substitute(templates[0], stub["pathParams"], False)
    patterns = stub.get("pathPatterns")
    if patterns is None:
        if len(templates) != 1 or "{" in templates[0]:
            raise StubError(f"{templates} needs pathParams or pathPatterns")
        return "urlPath", contract.base + templates[0]
    for operation_id in operation_ids:
        for name in patterns:
            contract.parameter_schema(operation_id, "path", name)
    expanded = []
    for template in dict.fromkeys(templates):
        expanded.append(substitute(template, patterns, True))
    root = min(expanded, key=len)
    suffixes = [e[len(root) + 1:] for e in expanded if e != root]
    if len(expanded) == 1:
        pattern = root
    elif all(e == root or e.startswith(root + "/") for e in expanded) and all("/" not in s for s in suffixes):
        pattern = root + "(/(" + "|".join(suffixes) + "))?"
    else:
        pattern = "(" + "|".join(expanded) + ")"
    return "urlPathPattern", re.escape(contract.base) + pattern


def build(contract, stub):
    operation_ids = stub["operations"] if "operations" in stub else [stub["operation"]]
    methods = {contract.op(o)["method"] for o in operation_ids}
    method = stub.get("method") or (methods.pop() if len(methods) == 1 else None)
    if method is None or (method != "ANY" and method not in {contract.op(o)["method"] for o in operation_ids}):
        raise StubError(f"{operation_ids}: method must be one declared by the contract, or ANY")
    response_spec = stub["response"]
    status = response_spec["status"]

    request = {"method": method}
    key, value = url_matcher(contract, operation_ids, stub)
    request[key] = value
    if "headers" in stub:
        request["headers"] = {}
        for name, matcher in stub["headers"].items():
            for operation_id in operation_ids:
                schema = contract.parameter_schema(operation_id, "header", name)["schema"]
                if "equalTo" in matcher:
                    validate(contract.file, schema, matcher["equalTo"], f"header {name}")
            request["headers"][name] = matcher
    if stub.get("matchRequired"):
        body = contract.op(operation_ids[0])["operation"].get("requestBody")
        if body is None:
            raise StubError(f"{operation_ids[0]} has no request body to match")
        _, schema = deref(contract.file, next(iter(body["content"].values()))["schema"])
        request["bodyPatterns"] = [{"matchesJsonPath": f"$.{member}"} for member in schema["required"]]

    contents = []
    for operation_id in operation_ids:
        declared = contract.response(operation_id, status)
        contents.append(declared)
    declared = contents[0]
    media_type, content = next(iter(declared["content"].items()))
    response = {"status": status}
    if "delayMs" in response_spec:
        response["fixedDelayMilliseconds"] = response_spec["delayMs"]
    headers = {"Content-Type": media_type}
    for name, header_value in response_spec.get("headers", {}).items():
        if name not in declared.get("headers", {}):
            raise StubError(f"{operation_ids[0]} {status} declares no response header {name}")
        headers[name] = header_value
    response["headers"] = headers
    if "body" in response_spec:
        body = response_spec["body"]
    elif media_type == "application/problem+json":
        _, problem = deref(contract.file, content["schema"])
        body = {"type": problem["properties"]["type"]["default"],
                "title": http.HTTPStatus(status).phrase, "status": status}
    else:
        raise StubError(f"{operation_ids[0]} {status}: a {media_type} body is required")
    validate(contract.file, content["schema"], body, f"{'+'.join(operation_ids)} {status} body")
    response["jsonBody"] = body

    template = " | ".join(dict.fromkeys(contract.op(o)["template"] for o in operation_ids))
    name = f"{'+'.join(operation_ids)} - {method} {contract.base}{template} -> {status}"
    if stub.get("label"):
        name += f" - {stub['label']}"
    name += f" (contract: {contract.name})"
    mapping = {"name": name}
    if "priority" in stub:
        mapping["priority"] = stub["priority"]
    if "scenario" in stub:
        mapping["scenarioName"] = stub["scenario"]["name"]
        mapping["requiredScenarioState"] = stub["scenario"]["requiredState"]
        if "newState" in stub["scenario"]:
            mapping["newScenarioState"] = stub["scenario"]["newState"]
    mapping["request"] = request
    mapping["response"] = response
    mapping["metadata"] = {"generatedBy": GENERATOR, "contract": contract.name,
                           "operationIds": operation_ids}
    return mapping


# The three edges consumed by the gateway: every one must be derived, none may stay hand-written.
REQUIRED_CONTRACTS = (
    "souscription-gateway-to-souscription-catalogue-service",
    "souscription-gateway-to-souscription-simulation-service",
    "souscription-gateway-to-souscription-service",
)


def main():
    with open(fixtures_file, encoding="utf-8") as handle:
        fixtures = yaml.safe_load(handle)
    covered = {spec["contract"] for spec in fixtures["services"].values()}
    missing = [name for name in REQUIRED_CONTRACTS if name not in covered]
    if missing:
        raise SystemExit(f"FAIL: consumed contracts not covered by {os.path.basename(fixtures_file)}: "
                         f"{', '.join(missing)}")
    rendered = {}
    for service, spec in fixtures["services"].items():
        contract = Contract(spec["contract"])
        mappings = []
        for index, stub in enumerate(spec["stubs"]):
            try:
                mappings.append(build(contract, stub))
            except StubError as error:
                raise SystemExit(f"FAIL: {service} stub #{index + 1}: {error}")
        rendered[os.path.join(output_dir, spec["output"])] = \
            json.dumps({"mappings": mappings}, indent=2, ensure_ascii=False) + "\n"

    drift = []
    for path, text in rendered.items():
        current = open(path, encoding="utf-8").read() if os.path.exists(path) else None
        if current != text:
            drift.append(path)
            if mode == "write":
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as handle:
                    handle.write(text)
    for root, _, files in os.walk(output_dir):
        for file in files:
            path = os.path.join(root, file)
            if file.endswith(".json") and path not in rendered:
                raise SystemExit(f"FAIL: {path} is not derived from a contract; remove it or add it to "
                                 f"{os.path.basename(fixtures_file)}")
    relative = [os.path.relpath(p, output_dir) for p in drift]
    if mode == "check" and drift:
        raise SystemExit(f"FAIL: stubs out of sync with the contracts: {', '.join(relative)}; "
                         f"run {GENERATOR}")
    count = sum(len(json.loads(t)["mappings"]) for t in rendered.values())
    print(f"OK: {count} mappings derived from {len(rendered)} contracts"
          + (f", rewritten: {', '.join(relative)}" if drift and mode == "write" else ", unchanged"))


main()
PYTHON
