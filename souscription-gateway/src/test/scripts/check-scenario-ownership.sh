#!/usr/bin/env bash
####
# Controle de la matrice d'allocation des scenarios Gherkin (W7-T01, stage gherkin-allocation).
#
# Compare les titres Scenario / Scenario Outline des fichiers features/*/*.feature avec
# features/SCENARIO_OWNERSHIP.json et echoue (code 1) si :
#   - le total des scenarios differe du total attendu (88), compte separement par mot-cle
#     (66 « Scenario: » + 22 « Scenario Outline: ») ;
#   - la matrice n'est pas un tableau JSON de 88 entrees ;
#   - un scenario n'est alloue nulle part (orphelin) ;
#   - un scenario est alloue plus d'une fois (double allocation) ;
#   - une entree de la matrice ne correspond plus a aucun scenario (entree perimee) ;
#   - le nombre de scenarios possedes par souscription-gateway differe de 24 ;
#   - la balise proprietaire d'un scenario (@<owner>) contredit la matrice ;
#   - features/SCENARIO_OWNERSHIP.md ne reflete pas la matrice JSON.
#
# Usage : bash souscription-gateway/src/test/scripts/check-scenario-ownership.sh
####
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
FEATURES_DIR="${FEATURES_DIR:-${REPO_ROOT}/features}"

command -v python3 >/dev/null 2>&1 || { echo "check-scenario-ownership: python3 est requis" >&2; exit 2; }

python3 - "${FEATURES_DIR}" <<'PY'
import collections
import glob
import json
import os
import re
import sys

features_dir = sys.argv[1]
EXPECTED_TOTAL = 88
EXPECTED_BY_KEYWORD = {"Scenario": 66, "Scenario Outline": 22}
EXPECTED_GATEWAY = 24
OWNERS = (
    "souscription-gateway",
    "souscription-web",
    "souscription-service",
    "souscription-catalogue-service",
    "souscription-simulation-service",
    "end-to-end",
)
# Anciennes balises proprietaires : leur presence est une contradiction avec la matrice.
OWNER_TAGS = {"@" + o for o in OWNERS} | {"@souscription-simulation", "@souscription-catalogue"}
HEADING = re.compile(r"^\s*(Scenario Outline|Scenario):\s*(.+?)\s*$")

errors = []


def tags_above(lines, index):
    """Tags of the tag lines directly above lines[index] (comments and blank lines skipped)."""
    tags = []
    j = index - 1
    while j >= 0:
        stripped = lines[j].strip()
        if stripped.startswith("@"):
            tags.extend(stripped.split())
        elif stripped.startswith("#") or stripped == "":
            if tags:
                break
        else:
            break
        j -= 1
    return tags


# 1. Scenarios declares dans les fichiers .feature
declared = []  # (feature, title, keyword, owner tags)
for path in sorted(glob.glob(os.path.join(features_dir, "*", "*.feature"))):
    feature = os.path.basename(os.path.dirname(path))
    with open(path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")
    feature_tags = []
    for i, line in enumerate(lines):
        if line.startswith("Feature:"):
            feature_tags = tags_above(lines, i)
            break
    for i, line in enumerate(lines):
        match = HEADING.match(line)
        if match:
            effective = {t for t in feature_tags + tags_above(lines, i) if t in OWNER_TAGS}
            declared.append((feature, match.group(2), match.group(1), effective))

if not declared:
    print(f"check-scenario-ownership: aucun scenario trouve sous {features_dir}", file=sys.stderr)
    sys.exit(1)

declared_count = collections.Counter((f, t) for f, t, _, _ in declared)
for key, count in declared_count.items():
    if count > 1:
        errors.append(f"titre declare {count} fois dans features/{key[0]} : « {key[1]} »")

# 2. Matrice JSON
matrix_path = os.path.join(features_dir, "SCENARIO_OWNERSHIP.json")
try:
    with open(matrix_path, encoding="utf-8") as handle:
        matrix = json.load(handle)
except (OSError, ValueError) as exc:
    print(f"check-scenario-ownership: matrice illisible {matrix_path} : {exc}", file=sys.stderr)
    sys.exit(1)

if not isinstance(matrix, list):
    print(f"check-scenario-ownership: {matrix_path} doit etre un tableau JSON d'entrees de scenario",
          file=sys.stderr)
    sys.exit(1)
entries = matrix
allocated = collections.Counter()
owner_of = {}
for entry in entries:
    missing = [k for k in ("feature", "scenarioTitle", "owner", "executingSuite") if not entry.get(k)]
    if missing:
        errors.append(f"entree incomplete ({', '.join(missing)} manquant) : {entry}")
        continue
    if entry["owner"] not in OWNERS:
        errors.append(f"proprietaire inconnu « {entry['owner']} » pour « {entry['scenarioTitle']} »")
    key = (entry["feature"], entry["scenarioTitle"])
    allocated[key] += 1
    owner_of[key] = entry["owner"]

declared_keys = set(declared_count)
orphans = sorted(declared_keys - set(allocated))
doubles = sorted(k for k, c in allocated.items() if c > 1)
stale = sorted(set(allocated) - declared_keys)
for f, t in orphans:
    errors.append(f"scenario orphelin (non alloue) : features/{f} « {t} »")
for f, t in doubles:
    errors.append(f"double allocation ({allocated[(f, t)]}x) : features/{f} « {t} »")
for f, t in stale:
    errors.append(f"entree perimee (aucun scenario de ce titre) : features/{f} « {t} »")

total = len(declared)
if total != EXPECTED_TOTAL:
    errors.append(f"total des scenarios declares = {total}, attendu {EXPECTED_TOTAL}")
if len(entries) != EXPECTED_TOTAL:
    errors.append(f"entrees de la matrice = {len(entries)}, attendu {EXPECTED_TOTAL}")
keyword_count = collections.Counter(k for _, _, k, _ in declared)
print(f"Scenarios declares : {keyword_count['Scenario']} Scenario + "
      f"{keyword_count['Scenario Outline']} Scenario Outline = {total}")
for keyword, expected in EXPECTED_BY_KEYWORD.items():
    if keyword_count[keyword] != expected:
        errors.append(f"« {keyword}: » declares = {keyword_count[keyword]}, attendu {expected}")
declared_keyword = {(f, t): k for f, t, k, _ in declared}
for entry in entries:
    key = (entry.get("feature"), entry.get("scenarioTitle"))
    if key in declared_keyword and entry.get("keyword") != declared_keyword[key]:
        errors.append(f"mot-cle incoherent pour features/{key[0]} « {key[1]} » : "
                      f"{entry.get('keyword')}, attendu {declared_keyword[key]}")

per_owner = collections.Counter(owner_of[k] for k in declared_keys if k in owner_of)
if per_owner["souscription-gateway"] != EXPECTED_GATEWAY:
    errors.append(f"scenarios possedes par souscription-gateway = "
                  f"{per_owner['souscription-gateway']}, attendu {EXPECTED_GATEWAY}")

# 3. Balises proprietaires : exactement @<owner> de la matrice
for feature, title, _, tags in declared:
    owner = owner_of.get((feature, title))
    if owner and tags != {"@" + owner}:
        found = " ".join(sorted(tags)) or "aucune"
        errors.append(f"balise proprietaire incoherente pour features/{feature} « {title} » : "
                      f"{found}, attendu @{owner}")

# 4. Vue lisible : features/SCENARIO_OWNERSHIP.md
md_path = os.path.join(features_dir, "SCENARIO_OWNERSHIP.md")
md_rows = collections.Counter()
current = None
row = re.compile(r"^\|\s*(.+?)\s*\|\s*(Scenario Outline|Scenario)\s*\|\s*`([^`]+)`\s*\|\s*$")
section = re.compile(r"^###\s+`features/([^/]+)/")
try:
    with open(md_path, encoding="utf-8") as handle:
        for line in handle:
            heading = section.match(line)
            if heading:
                current = heading.group(1)
                continue
            match = row.match(line.rstrip("\n"))
            if match and current:
                md_rows[(current, match.group(1), match.group(3))] += 1
except OSError as exc:
    errors.append(f"vue lisible absente : {md_path} ({exc})")
json_rows = collections.Counter((e.get("feature"), e.get("scenarioTitle"), e.get("owner")) for e in entries)
if md_rows and md_rows != json_rows:
    for f, t, o in sorted((json_rows - md_rows).keys()):
        errors.append(f"SCENARIO_OWNERSHIP.md ne liste pas features/{f} « {t} » -> {o}")
    for f, t, o in sorted((md_rows - json_rows).keys()):
        errors.append(f"SCENARIO_OWNERSHIP.md liste une ligne absente de la matrice : features/{f} « {t} » -> {o}")
elif not md_rows:
    errors.append("SCENARIO_OWNERSHIP.md ne contient aucune ligne d'allocation")

allocated_once = sum(1 for k in declared_keys if allocated.get(k) == 1)
print("Matrice d'allocation Gherkin (features/SCENARIO_OWNERSHIP.json)")
for owner in OWNERS:
    print(f"  {owner:<33} {per_owner[owner]:>3}")
print(f"  {'total alloue une seule fois':<33} {allocated_once:>3}")
print(f"{allocated_once}/{total} allocated, {len(orphans)} orphan, {len(doubles)} double allocation, "
      f"{len(stale)} stale entry")

if errors:
    print(f"\nECHEC : {len(errors)} anomalie(s)", file=sys.stderr)
    for error in errors:
        print(f"  - {error}", file=sys.stderr)
    sys.exit(1)
print("OK")
PY
