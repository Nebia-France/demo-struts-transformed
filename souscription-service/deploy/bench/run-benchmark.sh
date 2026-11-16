#!/usr/bin/env bash
# Banc NFG-007..NFG-013 de souscription-service (plan 40, W8-T05).
#
# Prerequis : JDK 21, Maven, un PostgreSQL joignable par BENCH_DB_ADMIN_URL (defaut
# jdbc:postgresql://localhost:5432/postgres) avec un compte autorise a creer une base
# (BENCH_DB_USER / BENCH_DB_PASSWORD, lus dans l'environnement, jamais codes en dur).
#
# Usage (depuis la racine du depot) :
#   souscription-service/deploy/bench/run-benchmark.sh            # build gate chronometre + banc
#   SKIP_BUILD=1 souscription-service/deploy/bench/run-benchmark.sh  # banc seul (artefact existant)
#   BENCH_RUNTIME=docker SKIP_BUILD=1 souscription-service/deploy/bench/run-benchmark.sh
#       # repliques = conteneurs de l'image Jib (BENCH_IMAGE) sous --memory 512m (limite de
#       # deploy/deployment.yaml) ; memoire = working set du conteneur (docker stats). L'image doit
#       # avoir ete construite : mvn -f souscription-service/pom.xml package -DskipTests
#       #   -Dquarkus.container-image.build=true
#
# Sorties : souscription-service/target/benchmark/ (results.json, journaux, captures /q/metrics,
# build-gate.log avec la duree de pipeline).
set -euo pipefail

MODULE="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$MODULE/target/benchmark"

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  start=$(date +%s)
  mvn -f "$MODULE/pom.xml" clean verify -Pcucumber > "$MODULE/target-build-gate.log" 2>&1
  end=$(date +%s)
  mkdir -p "$OUT"
  mv "$MODULE/target-build-gate.log" "$OUT/build-gate.log"
  echo "pipelineSeconds=$((end - start))" | tee "$OUT/build-duration.txt"
fi

PG_VERSION="$(mvn -q -f "$MODULE/pom.xml" help:evaluate -Dexpression=postgresql.version -DforceStdout)"
M2_REPO="$(mvn -q -f "$MODULE/pom.xml" help:evaluate -Dexpression=settings.localRepository -DforceStdout)"
PG_JAR="$M2_REPO/org/postgresql/postgresql/$PG_VERSION/postgresql-$PG_VERSION.jar"
mkdir -p "$OUT"
cd "$MODULE"
java -cp "$PG_JAR:$MODULE/target/test-classes" "$MODULE/deploy/bench/Benchmark.java" \
  | tee "$OUT/benchmark-stdout.log"
