#!/usr/bin/env bash
# W7-T04 - declarative check of the downstream network isolation (plan 19, docs/network-isolation.md).
#
# Verifies, without a cluster, the invariants the live procedure of docs/network-isolation.md relies
# on:
#   1. a default-deny ingress NetworkPolicy selects the three downstream services;
#   2. the only allow rule admits pods labelled app=souscription-gateway, on TCP 8080 only;
#   3. no manifest under deploy/ publishes a downstream (no Ingress, no LoadBalancer/NodePort);
#   4. the local same-origin ingress (deploy/local/ingress.conf) proxies only to the gateway and
#      the SPA, and the compose topology publishes no downstream port.
# The live probes (steps 1-3 of the doc) still run in the cluster and stay with the
# infrastructure team.
#
# Exit code 0 = all invariants hold.

set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

python3 - "${MODULE_DIR}" <<'EOF'
import pathlib, re, sys
import yaml

module = pathlib.Path(sys.argv[1])
downstreams = {"souscription-catalogue-service", "souscription-simulation-service", "souscription-service"}
failures = []

def selected(policy):
    sel = policy["spec"]["podSelector"]
    names = set()
    for expr in sel.get("matchExpressions", []):
        if expr["key"] == "app" and expr["operator"] == "In":
            names |= set(expr["values"])
    if sel.get("matchLabels", {}).get("app"):
        names.add(sel["matchLabels"]["app"])
    return names

policies = [d for d in yaml.safe_load_all((module / "deploy/networkpolicy.yaml").read_text())
            if d and d.get("kind") == "NetworkPolicy"]

deny = [p for p in policies if p["spec"].get("ingress") == [] and "Ingress" in p["spec"]["policyTypes"]
        and selected(p) >= downstreams]
if not deny:
    failures.append("no default-deny ingress policy selecting the three downstream services")

for p in policies:
    if not selected(p) & downstreams:
        continue
    for rule in p["spec"].get("ingress", []):
        sources = rule.get("from", [])
        if not sources:
            failures.append(f"{p['metadata']['name']}: ingress rule without 'from' (open to all)")
        for src in sources:
            if src.get("podSelector", {}).get("matchLabels") != {"app": "souscription-gateway"} \
                    or "namespaceSelector" in src or "ipBlock" in src:
                failures.append(f"{p['metadata']['name']}: source other than app=souscription-gateway: {src}")
        ports = rule.get("ports", [])
        if ports != [{"protocol": "TCP", "port": 8080}]:
            failures.append(f"{p['metadata']['name']}: ports other than TCP 8080: {ports}")

for f in sorted((module / "deploy").glob("*.yaml")):
    for d in yaml.safe_load_all(f.read_text()):
        if not d:
            continue
        text = yaml.safe_dump(d)
        if d.get("kind") == "Ingress" and any(s in text for s in downstreams):
            failures.append(f"{f.name}: Ingress references a downstream service")
        if d.get("kind") == "Service" and d.get("spec", {}).get("type") in ("LoadBalancer", "NodePort"):
            failures.append(f"{f.name}: Service of type {d['spec']['type']}")

conf = (module / "deploy/local/ingress.conf").read_text()
upstreams = set(re.findall(r"proxy_pass\s+([^;]+);", conf))
if upstreams - {"$gateway", "$web"}:
    failures.append(f"ingress.conf proxies to {upstreams - {'$gateway', '$web'}}")

compose = yaml.safe_load((module / "docker-compose.dev.yml").read_text())
for name, svc in compose.get("services", {}).items():
    downstream_like = name in downstreams or name.endswith("-stub")
    if downstream_like and svc.get("ports"):
        failures.append(f"docker-compose service {name} publishes ports {svc['ports']}")

for f in failures:
    print("FAIL:", f, file=sys.stderr)
if failures:
    sys.exit(1)
print("OK: default-deny on 3 downstreams, gateway-only allow on TCP 8080, 0 downstream published "
      "(deploy/, local ingress, compose)")
EOF
