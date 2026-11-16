# MIGRATION_AUDIT — souscription-simulation-service (W7-T05)

Pre-cut-over audit record. It changes no production code; it records what was **run**
and what was **measured** on this host, and names the owner of every item that cannot
be measured here.

Audit run: 2026-11-09. Host: macOS on Apple silicon, 15 cores, single node, no
Kubernetes cluster, no container runtime (`docker` absent), no outbound route to the
NVD data feed. Artefact under test: `target/quarkus-app/` fast-jar of
`souscription-simulation-service` 1.0.0-SNAPSHOT on Temurin 21.0.11+10 LTS.

**Verdict: cut-over gate PASSED for every criterion measurable in this environment.**
The items that cannot be measured here are deferred to the integration/cluster
environment: **7 non-functional-target deferrals** in §6.1 and the **18-line deferral
registry inherited from the earlier parity checks** in §6.2, each with a named owner and
a due date (two of its lines are closed by live measurement in this run). None of them is
an accepted gap, and none is recorded as passed.

---

## 1. Validation sequence executed

| # | Step | Command | Result |
| --- | --- | --- | --- |
| 1 | Full build, Cucumber barrier included | `mvn -f souscription-simulation-service/pom.xml clean verify -Pcucumber` | **BUILD SUCCESS** — 340 tests, 0 failures, 0 errors, 2 skipped (both conditional assumptions, no `@Disabled`: `SizingCacheDecisionParityTest` skips its cache-identity case while no cache is configured, `HealthProbeParityTest` skips its cluster case). Cucumber: 42 cases / 10 scenarios, 0 failures. Wall time 51.4 s |
| 2 | Load campaign (throughput, p50/p95/p99, payload) | `.ci/templates/latency-budget.sh souscription-simulation-service /v1/simulations 15 50 120 1024` | **LATENCY BUDGET PASSED** — `{"concurrency": 8, "samples": 4000, "p50_ms": 1.042, "p95_ms": 2.068, "p99_ms": 3.82, "mean_ms": 1.218, "max_ms": 15.63, "request_bytes": 93, "response_bytes_max": 128}`, 0 failed request |
| 3 | Overload / rate-limit behaviour | `SimulationOverloadTest` (1 test), `FaultInjectionTest` (4 tests) in step 1 + live flood: 3 000 worst-case requests, concurrency 64, contracted gateway identity, production limiter settings | Tests green. Live flood: **339 × 200** `application/json`, **2 661 × 429** `application/problem+json`, **0 × 5xx, 0 × 500, 0 connection hang**. The limiter (100 req/s, burst 200) is reached before the bulkhead at this concurrency, which is the contracted precedence; the `503` capacity path is asserted separately by `SimulationOverloadTest` (saturation beyond 20 + 40 ⇒ 503 problem+json, no 500, no 429) |
| 4 | Fault injection (timeout, bulkhead, circuit semantics) | `FaultInjectionTest`, `OverloadRejectionParityTest`, `IdempotencyFaultParityTest` in step 1 | Green — server timeout answers inside 2 500 ms and below the 3 s caller timeout; bulkhead overflow ⇒ 503; rate excess ⇒ 429; FT metrics present; validation still precedes capacity (400 under saturation) |
| 5 | Dependency CVE scan | `mvn -f souscription-simulation-service/pom.xml org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7` **did run** on this host (the NVD feed is reachable; only the incremental NVD/RetireJS refresh fails with `NoRouteToHostException`, so the cached NVD data is used — `[WARNING] Unable to update 1 or more Cached Web DataSource`). Cross-checked with a local CVE/OSV database query on the resolved coordinates (`mvn dependency:list`) | **First run FAILED**: two HIGH findings, `CVE-2026-15075` and `CVE-2026-15076` (CVSSv4 8.2) against `vertx-web-4.5.27.jar` and `vertx-grpc-server-4.5.27.jar` — both affect Vert.x 4.x up to and including 4.5.29. **Remediated** by raising the pin `vertx.version` 4.5.27 → **4.5.34** (upgrade only). **Re-run: 0 finding at CVSS ≥ 7** (`BUILD SUCCESS` with `-DfailBuildOnCVSS=7`); the two residual findings are MEDIUM and below the gate: `CVE-2026-89044` on `netty-transport 4.1.137.Final` (6.9/6.5) and `CVE-2026-54285` on `opentelemetry-api(-incubator) 1.44.1` (5.3), both NVD/CPE matches carried by the Quarkus platform. Local CVE/OSV DB: **0 vulnerability, OVERALL_CVE_STATUS: PASS** (snapshot 2026-11-08) for `quarkus-bom 3.20.6.2`, `netty-bom 4.1.137.Final`, `jackson-bom 2.18.11`, `micrometer-bom 1.15.12`, `vertx-dependencies 4.5.34`, `guava 33.7.2-jre`, `snakeyaml 2.3` — note the two sources disagree: NVD/CPE matching flagged Vert.x 4.5.27, OSV did not, so the NVD verdict was taken as authoritative |
| 6 | Container image scan | **not run** — no container runtime and no Trivy on this host | Deferred, §6 |
| 7 | mTLS conformance | `MutualTlsConformanceTest` (4 tests) in step 1 + live `curl` against the fast-jar on the production transport | Tests green. Live: **no client certificate ⇒ handshake refused** (curl exit 56, no HTTP status, i.e. rejected before any request exists); **PKI-valid but uncontracted identity ⇒ 403** `application/problem+json` (`"Appelant non autorisé"`, no disclosure of the accepted list); **contracted identity ⇒ 200** with the five contracted fields; **TLS 1.2 forced ⇒ refused** (curl exit 35); three health endpoints answer 200 on the management port 9000 only |
| 8 | OpenAPI diff against both frozen contracts | `.ci/templates/openapi-contract-diff.py target/openapi.yaml <2 frozen contracts>` and the full suite `.ci/templates/contract-suite.sh …` | **Diff empty** — "generated document matches 2 frozen contract(s)". `CONTRACT SUITE PASSED`: 67 tagged contract tests, 0 failures, then the diff, then publication of `souscription-simulation-service-openapi-1.0.0.yaml` |

**Parity re-verification, 2026-11-09.** The nine verification steps of
`31-simulation-end-to-end-validation-audit-parity` were re-run on this record: gate C green
(340 tests, 0 failures, 2 assumption skips; Cucumber 42 cases / 10 scenarios), OpenAPI diff
empty against both frozen contracts, scenario count re-derived from the feature files
(`Scenario:` 66 + `Scenario Outline:` 22 = 88 ⇒ 10 in scope + 78 attributed), no
`@Disabled`/`@Ignore` anywhere, `git status --porcelain -- src features global pom.xml`
empty, and `dependency-check-maven:check -DfailBuildOnCVSS=7` executed against the cached
NVD data (only the incremental feed refresh is unreachable offline), which **failed** on two
HIGH Vert.x findings (`CVE-2026-15075`, `CVE-2026-15076`, 8.2) and passed after the
`vertx.version` 4.5.27 → 4.5.34 upgrade; the local CVE/OSV query was re-run on the resolved
coordinates (`OVERALL_CVE_STATUS: PASS`, 0 vulnerability, DB snapshot 2026-11-08). The re-run found the
deferral record incomplete — the preamble under-counted the deferrals and several inherited
ones were absent — which §6.2 now closes.

One pipeline defect was found and fixed by this audit: `.ci/templates/smoke-test.sh`
started the fast-jar with no TLS environment, so it could not boot a service whose
only listener is mTLS (`Cannot set quarkus.http.insecure-requests without enabling
SSL`) — a blocking stage that would have failed on every run. The script now presents
the same throwaway certificates as `latency-budget.sh` (overridable by
`TLS_CERT_PATH` / `TLS_KEY_PATH` / `TLS_CA_PATH`); services with no TLS ignore them.

## 2. The 19 non-functional targets, measured

| Gap | Target | Measured |
| --- | --- | --- |
| NFG-001 | p95 server ≤ 50 ms, p95 caller ≤ 150 ms, payload < 1 KB | **p95 server 2.068 ms** (p50 1.042 ms, p99 3.82 ms, mean 1.218 ms, max 15.63 ms, 4 000 samples, concurrency 8, worst-case body, 0 failed request). **Request 93 B / response 128 B**, both < 1 024 B, and bounded by construction (four scalars each way). Caller-side p95: **not measurable here** — no in-cluster hop and no caller deployed; the server figure leaves ≈ 148 ms of the 150 ms caller budget to the network. PASS server-side, caller leg deferred (§6) |
| NFG-002 | startup < 2 s | **0.814 s** (`smoke-test.sh`, fast-jar, `startup_seconds=0.814 budget=2`), second run 0.784 s, third 1.709 s on a loaded host — all < 2 s. PASS |
| NFG-003 | 0 blocked event loop; ≥ 500 simulations/s/core | **0 blocked-thread-checker warning** (`EventLoopSafetyTest`, 64 worst-case requests at concurrency 16, green). Per-core capacity asserted green by `SimulationThroughputTest` (`ThreadMXBean` CPU time over 5 000 worst-case iterations, fails below the 500/s/core floor); recorded figure ~88 µs CPU per worst-case request ⇒ **~11 300 simulations/s/core**. PASS |
| NFG-004 | 120–200 MB RSS, limit 384Mi | **Not comparable on this host.** Boot-instant RSS **210 MB**, settled resting RSS **51–52 MB** (three samples 20–30 s after boot). Both are measured with `-XX:MaxRAMPercentage=70` of 64 GB of host RAM and **no cgroup limit**, so the heap budget is not the one the 120–200 MB band describes; the band is a 384Mi-container figure. The manifest sizing (`requests 192Mi`, `limits 384Mi`) is unchanged and the footprint is structurally flat (no datasource, no pool, no cache). Cluster measurement deferred (§6) |
| NFG-005 | pipeline < 5 min | **~1 min 40 s of compute** for the gates runnable here: `clean verify -Pcucumber` 51.4 s + contract suite 17.7 s + smoke test 1.1 s + latency campaign 6.7 s ≈ **77 s**, plus the image build/scan and deploy stages not runnable here. Inside the 5 min budget with margin. PASS (local), full-CI duration deferred (§6) |
| NFG-006 | 0 CVE HIGH+; CVE-2025-66560 absent | **0 finding at CVSS ≥ 7** from OWASP Dependency-Check 13.0.0 (`-DfailBuildOnCVSS=7` → `BUILD SUCCESS`) after upgrading the Vert.x pin 4.5.27 → **4.5.34**, which cleared the two HIGH findings `CVE-2026-15075` / `CVE-2026-15076` (8.2, Vert.x 4.x ≤ 4.5.29) on `vertx-web` and `vertx-grpc-server`. Residual findings are MEDIUM only, below the gate: `CVE-2026-89044` (netty-transport 4.1.137.Final, 6.9) and `CVE-2026-54285` (opentelemetry-api/-incubator 1.44.1, 5.3). Local CVE/OSV DB: `OVERALL_CVE_STATUS: PASS`, 0 vulnerability (snapshot 2026-11-08). **CVE-2025-66560 absent**: it affects Quarkus 3.20.0 and the resolved platform is **3.20.6.2** (also past 3.20.5, which carried CVE-2026-50559 / CVE-2026-39852 HIGH) |
| NFG-007 | Java 21, TLS 1.3 only | **Java 21.0.11 LTS** (`openjdk version "21.0.11" 2026-04-21 LTS`, Temurin 21.0.11+10), `maven.compiler.release=21`. **TLS 1.3 only**: a forced TLS 1.2 handshake is refused (curl exit 35) while the TLS 1.3 call returns 200; `quarkus.http.ssl.protocols=TLSv1.3`, two contracted cipher suites. PASS |
| NFG-008 | 1 internal mTLS endpoint, 0 external exposure | **1 traffic endpoint** (`POST /v1/simulations` on 8443, mTLS, client-auth required). **0 external exposure**: no `Ingress` and no `Route` object in `deploy/` (`grep -l "kind: Ingress\|kind: Route"` ⇒ none), `Service` is `ClusterIP` with one port, management port 9000 unpublished, `insecure-requests=disabled` — proved by the service refusing to boot at all when the certificate paths are absent, so there is no plaintext fallback. Handshake refused without a certificate; 403 for an uncontracted PKI identity. PASS |
| NFG-009 | 0 image HIGH/CRITICAL finding, non-root, read-only rootfs | **Image scan not run** (no container runtime / no Trivy here) — deferred, §6. Statically verified in the manifests that the scan would check: base image pinned **by digest** (`ubi9/openjdk-21-runtime@sha256:…`, no floating tag), `USER 185`, `runAsNonRoot: true`, `runAsUser: 185`, `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, only writable path a `tmpfs` on `/tmp`. PARTIAL — non-root and read-only rootfs PASS, finding count deferred |
| NFG-010 | 2..N replicas, 0 lost request on rollout | **0 lost request** measured on the single host: graceful-shutdown campaign 18 322 requests completed, **0 lost**, drain 1.06 s, exit 143; horizontal campaign at **2 replicas** (~31 600 req/s, p95 1 ms, **0 failed**) and **4 replicas** (~27 100 req/s, p95 1 ms, **0 failed**) establishing freedom from affinity. `kubectl rollout` under load deferred, §6 |
| NFG-011 | 503 on overload, explicit limits | **Explicit limits**: `@Bulkhead(value = 20, waitingTaskQueue = 40)`, `@Timeout(2500 ms)` < 3 000 ms caller timeout, `max-connections 200`, pool 8/16/queue 64, limiter 100 req/s burst 200. **503 on capacity overload** asserted green by `SimulationOverloadTest` (excess beyond 20 + 40 ⇒ 503 `application/problem+json`, no 500, no 429, no hung connection) and `OverloadRejectionParityTest`; **429 on rate excess** observed live (2 661/3 000, `application/problem+json`, no `Retry-After`), 0 × 500. PASS |
| NFG-012 | cache decision backed by measured duplicate ratio | **Ratio measured, cache deliberately not enabled.** Reference `mvn clean test` traffic: `simulation.inputs.distinct = 35`, `simulation.inputs.repeated = 265` ⇒ **repeated ratio 0.88**, and that figure is explicitly *not* read as evidence for a cache (it is dominated by the overload and parity tests replaying one payload — it measures the harness). Counters bounded (1 024-entry LRU of digests only) and untagged; `DuplicateInputRatioTest` and `SizingCacheDecisionParityTest` green, no `cache_gets` series exists. Revisit threshold recorded: sustained production repeated ratio > 0.5 while CPU is the scaling constraint. PASS |
| NFG-013 | 100% JSON, 100% correlated | **100% JSON** — every line of `target/smoke-test.log` and of the audit runs is a JSON object (`quarkus.log.console.json=true`, no `slf4j-simple` on the classpath), **parse failure rate 0**. **100% correlated** — each request line carries `traceId`, `spanId` and `correlationId` in `mdc` (observed in this run, e.g. `traceId=a5a211c8ae5248fddbbcf40841719686` with the matching `correlationId`), asserted by `AuditLogParityTest` (8 tests green). PASS |
| NFG-014 | HTTP+JVM+business metrics, 0 high-cardinality label | **Three families exposed** on the management port: `http_server_requests_seconds_*`, `jvm_*`, `simulation_calculate_seconds_*`, plus `simulation_ratelimit_rejected_total`, `ft_bulkhead_calls_total`, `worker_pool_*`, `simulation.inputs.{distinct,repeated}`. **0 high-cardinality label**: `grep -rn "Tags.of\|\.tag("  src/main/java` returns no match — no meter is tagged by amount, rate, correlation or trace id. `MetricsExpositionTest` (3) and `MetricsParityTest` green. `promtool` is absent here, so `promtool test rules` stays the CI `alert-rules-test` stage (§6) |
| NFG-015 | 100% of errors traced, complete 3-level trace | **100% of errors traced** whatever the sample ratio: every problem+json exit marks the span `ERROR` (`ProblemJson.response`) and `ErrorBiasedSampler` + `ErrorBiasedSpanProcessor` export every record-only `ERROR` span; `ErrorBiasedSamplingTest`, `TracePropagationTest` and `TracePropagationParityTest.allErrorResponsesAreTraced` green. Own leg complete (incoming `traceparent` continued, no new trace). **3-level trace gateway → service → simulation not verifiable from this repository** — deferred, §6 |
| NFG-016 | 3 endpoints, removal within 1 probe interval | **3 endpoints live, management port only**: `/q/health/live` 200, `/q/health/ready` 200, `/q/health/started` 200 on 9000; absent from the traffic port. `ready` reports the single check `simulation-worker-pool`. **Removal**: readiness turns DOWN as soon as the worker-pool queue reaches `queue-size` — i.e. within one 5 s probe interval of saturation — and liveness stays UP so a burst is not a restart loop (`WorkerPoolHealthCheckTest`, `ManagementHealthEndpointTest`, `HealthProbeParityTest` green; endpoint removal takes `failureThreshold: 3` intervals by design). PASS |
| NFG-017 | 4 constrained fields, 1 mapper, 0 behaviour difference | **4 constrained request fields** (`initialAmount`, `monthlyAmount`, `durationYears`, `annualRate`) covering the 11 traced legacy rules of `VALIDATION_TRACEABILITY.md`, **1 exception mapper** family exiting through `ProblemJson`, **0 behaviour difference**: the ported corpus is 100% green — `SimulationServiceTest` (3 methods verbatim, unchanged expected values and 0.005 tolerance, `scale == 2`), `SimulationInputValidatorTest`, `SimulationRequestConstraintTest` (11 examples), `DecimalContractTest`, `PortedTestFidelityParityTest` (byte-identical French messages and legacy error keys), `ContractItFidelityParityTest`. PASS |
| NFG-018 | Quarkus 3.20.x LTS, sub-second dev reload | **Quarkus 3.20.6.2** (resolved `quarkus-core:3.20.6.2`), latest patch of the 3.20 LTS stream, pinned once in `global/platform/pom.xml` and only *referenced* twice by this POM (never redefined). Sub-second reload **not measured in this run** (`quarkus:dev` is an interactive loop; the comparable cold signal measured here is the 0.784–0.814 s full JVM start, and augmentation completed in 1 548 ms). Version PASS, reload figure deferred, §6 |
| NFG-019 | 0 hand-written pipeline, 1 BOM, 1 contract suite | **0 bespoke pipeline file**: `souscription-simulation-service/.ci/pipeline.yml` is a parameter instance of `.ci/templates/quarkus-service-pipeline.yml`, and the deployment/network manifests are rendered from `.ci/templates/deploy/*` by `render-manifests.py`. **1 BOM**: a single `quarkus.platform.version` in the shared parent for the four services. **1 contract suite**: `.ci/templates/contract-suite.sh`, run by this pipeline and by both consumer pipelines, membership by `@Tag("contract")` — executed in this audit, 67 tests, PASSED. PASS |

## 3. OpenAPI diff against the frozen contracts

Re-run in this audit, twice (standalone and inside the suite):

```
.ci/templates/openapi-contract-diff.py souscription-simulation-service/target/openapi.yaml \
  global/contracts/souscription-gateway-to-souscription-simulation-service/openapi.yaml \
  global/contracts/souscription-service-to-souscription-simulation-service/openapi.yaml
→ OpenAPI contract diff empty: generated document matches 2 frozen contract(s).
```

`global/contracts/` is unmodified (`git diff --stat global/` empty). The versioned
artefact `souscription-simulation-service-openapi-1.0.0.yaml` was republished by the
suite and is the input both consumer pipelines assert against.

## 4. Declared coverage gaps and their compensation

| Declared gap (`RAPPORT_COUVERTURE_GHERKIN.md`) | Compensation, verified in this audit |
| --- | --- |
| Point 4 — the Gherkin scenarios do **not** check the exact final-capital values; they assert relations (equal to / greater than the contributed total) and error messages | `SimulationServiceTest` — the behavioural reference, ported verbatim: independent closed form over 4 `@CsvSource` sets with tolerance 0.005 and `scale == 2`, the zero-rate identity (`2400.00` / `0.00`), 7 invalid-parameter sets. `DecimalContractTest` — reference values captured on the legacy implementation (wave 3). Both green in step 1 |
| Point 3 — concurrent behaviour is **not expressible** in Gherkin | Wave 4 load and overload evidence: `SimulationThroughputTest`, `EventLoopSafetyTest` (0 blocked event loop at concurrency 16), `SimulationOverloadTest` (503 beyond 20 + 40), the 4 000-sample latency campaign at concurrency 8 and the 3 000-request flood at concurrency 64 run here. Wave 6 fault injection: `FaultInjectionTest`, `OverloadRejectionParityTest`, `IdempotencyFaultParityTest`. All green |

## 5. The 88 Gherkin scenarios — every one assigned

Counted from the feature files: 8 + 13 + 4 + 8 + 15 + 9 + 11 + 10 + 10 = **88**.
In scope here: **10**. Out of scope: **78**. No scenario is left unassigned, and no
`.feature` file was modified (`git diff --stat features/` empty).

### In scope — 10 scenarios, blocking barrier of this service (all green)

`features/simulation/simulation.feature`, 42 Cucumber cases, 0 failures:
`Simulation d'un placement standard`;
`Validation reussie des montants de simulation` (4 examples);
`Bornes du versement initial` (4); `Bornes de la mensualite` (3);
`Bornes de la duree en annees` (6); `Format de montant refuse` (6);
`Normalisation des montants saisis` (4);
`Parametres de simulation rejetes par le moteur de calcul` (12);
`Simulation avec une hypothese de progression nulle`; `Simulation sans mensualite`.

### Out of scope — 78 scenarios with their owning application

**`features/catalogue/catalogue.feature` (8) → souscription-catalogue-service / souscription-web**
`Consultation de la liste des placements`, `Consultation de la fiche d'un placement existant`,
`Le taux annuel est egalement expose en pourcentage`, `Consultation de la fiche d'un placement inconnu`,
`Code produit vide ou mal formate sur la fiche produit`, `Recherche d'un produit sans code transmis`,
`Methode HTTP non autorisee sur le catalogue`, `La liste des produits n'est pas modifiable`.

**`features/validation-souscripteur/validation-souscripteur.feature` (10) → souscription-service**
`Saisie complete et valide du souscripteur`,
`Les espaces superflus sont supprimes des donnees du souscripteur`, `Prenom ou nom invalide`,
`Longueurs limites acceptees pour le prenom`, `Un caractere de controle dans le nom est refuse`,
`Adresse electronique invalide`, `Une adresse electronique de plus de 254 caracteres est refusee`,
`Date de naissance invalide ou non conforme`, `Age limite de la majorite`,
`La construction du souscripteur est refusee en cas d'erreur de saisie`.

**`features/formulaire-souscription/formulaire-souscription.feature` (4) → souscription-service / souscription-web**
`Valeurs par defaut du formulaire`, `Les valeurs nulles sont remplacees par une chaine vide`,
`Les valeurs saisies sont conservees telles quelles`, `Acceptation des informations par l'utilisateur`.

**`features/parcours-souscription/parcours-souscription.feature` (15) → souscription-web / souscription-gateway / souscription-service**
`Demarrage du parcours depuis un produit du catalogue`, `Parcours complet jusqu'au recapitulatif`,
`Recalcul de la simulation sans quitter l'etape`,
`Les donnees deja saisies sont reaffichees a l'etape souscripteur`,
`Les donnees de simulation deja saisies sont reaffichees`,
`Saisie invalide du souscripteur a l'etape 2`, `Saisie invalide de la simulation a l'etape 3`,
`Commande inconnue a l'etape de simulation`, `Demarrage du parcours avec un produit inconnu`,
`Methode HTTP non conforme a l'etape du parcours`, `Chemin inconnu dans le parcours`,
`Reprise du parcours sans brouillon en session`,
`Acces a la simulation avant l'identification du souscripteur`,
`Acces au recapitulatif avant la simulation`,
`Modification du souscripteur apres simulation invalide la cle de confirmation`.
*`Recalcul de la simulation sans quitter l'etape` and `Saisie invalide de la simulation a
l'etape 3` traverse this service as the callee; they are executed by the
souscription-web / souscription-gateway pipelines against the published `/v1` API.*

**`features/confirmation-souscription/confirmation-souscription.feature` (13) → souscription-service**
`Confirmation d'une demande complete`, `Affichage de la page de confirmation`,
`Le capital final est recalcule au moment de la confirmation`,
`Double soumission de la confirmation avec la meme cle`,
`L'enregistrement est idempotent pour une cle de confirmation deja utilisee`,
`Cle de confirmation invalide`, `Conditions non acceptees`, `Indisponibilite de l'enregistrement`,
`Confirmation d'un parcours incomplet`, `Confirmation d'un parcours sans simulation`,
`Recherche d'une souscription par une cle inconnue`,
`Recherche d'une souscription enregistree par sa cle`,
`Consultation de la confirmation sans souscription aboutie`.
*`Le capital final est recalcule au moment de la confirmation` depends on this
service's API; per the confirmed "keep it remote" topology decision the test belongs
to the souscription-service pipeline, which calls `/v1/simulations`.*

**`features/persistance-souscriptions/persistance-souscriptions.feature` (9) → souscription-service**
`Creation du schema de la base`, `Enregistrement d'une souscription`,
`Enregistrement repete avec la meme cle de confirmation`,
`Conflit d'unicite concurrent sur la cle de confirmation`, `Echec technique a l'insertion`,
`Script de schema absent`, `Base de donnees injoignable`, `Relecture d'une cle inexistante`,
`Les donnees du produit et du souscripteur sont restituees a la relecture`.

**`features/securite-web/securite-web.feature` (11) → souscription-gateway / souscription-web**
`Entetes de securite ajoutes a chaque reponse`,
`L'encodage des requetes et des reponses est UTF-8`,
`Un jeton anti-CSRF est cree pour la session a l'acces d'une action`,
`Le jeton anti-CSRF existant est conserve`,
`Soumission acceptee avec le jeton anti-CSRF attendu`,
`Soumission refusee en cas de jeton anti-CSRF incorrect`,
`Soumission refusee en l'absence de jeton anti-CSRF`,
`Jeton anti-CSRF de plus de 100 caracteres refuse`, `Methodes HTTP non autorisees`,
`Methodes HTTP autorisees`, `Les ressources statiques ne requierent pas de jeton anti-CSRF`.

**`features/initialisation-application/initialisation-application.feature` (8) → souscription-service (datasource) / removed (H2 and POSIX bootstrap)**
`Demarrage nominal de l'application`, `Les permissions du repertoire de donnees sont restreintes`,
`Le chemin de la base est lu depuis la propriete systeme`,
`Le systeme de fichiers ne supporte pas les permissions POSIX`,
`Echec d'initialisation de l'application`, `Acces aux services avant initialisation`,
`Arret de l'application`, `Echec de liberation d'un pilote JDBC a l'arret`.

## 6. Items deferred to the integration / cluster environment

None is an accepted gap; each has a named owner and a due date.

### 6.1 Non-functional-target deferrals

| Item | Why it cannot be measured here | Owner / due date | Procedure |
| --- | --- | --- | --- |
| Caller-side p95 ≤ 150 ms (NFG-001), container RSS band 120–200 MB under a 384Mi limit (NFG-004), `kubectl rollout` under load with 0 lost request and the one-replica-per-node scaling figure (NFG-010) | no Kubernetes cluster and no cgroup memory limit on this host | souscription-simulation-service platform owner (service DRI) — due 2026-11-30 | deploy, run the load generator against the `Service`, `kubectl rollout restart` + `rollout status`, read `container_memory_working_set_bytes` and the consumer-side timer |
| Image scan, 0 HIGH/CRITICAL finding (NFG-009) | no container runtime and no Trivy installed | souscription-simulation-service platform owner (service DRI) — due 2026-11-30 | the blocking `image-scan` stage of `.ci/templates/quarkus-service-pipeline.yml` (`trivy image --severity HIGH,CRITICAL --exit-code 1`) |
| NVD incremental feed refresh during the Dependency-Check run (NFG-006 *gate itself now runs and passes* against the cached NVD data — no longer deferred) | the incremental data-source update fails (`NoRouteToHostException`), so the cached NVD snapshot is used and very recent CVEs may be missing | souscription-simulation-service platform owner (service DRI) — due 2026-11-30 | re-run the blocking `dependency-scan` stage, which has the live feed, to confirm the 0-HIGH verdict against up-to-date data |
| `promtool check rules` / `test rules` (NFG-014 rule half) | `promtool` not installed | souscription-simulation-service platform owner (service DRI) — due 2026-11-30 | the blocking `alert-rules-test` stage |
| 3-level trace gateway → souscription-service → simulation (NFG-015) | requires the two sibling applications deployed against the same collector | service DRI jointly with the gateway and subscription-service owners — due 2026-11-30 | the four-step procedure in `README.md` §Tracing; a missing third level is a finding, not an accepted gap |
| Full-CI pipeline duration (NFG-005 — local gates measured at ~77 s) | image build, scan, publish and deploy stages not runnable here | souscription-simulation-service platform owner (service DRI) — due 2026-11-30 | read the CI job duration against the 5 min budget |
| Sub-second `quarkus:dev` reload (NFG-018 — version PASS) | interactive dev loop, not a batch gate | souscription-simulation-service platform owner (service DRI) — due 2026-11-30 | `quarkus:dev`, edit `SimulationService`, read the reload line |

### 6.2 Deferral registry inherited from the parity checks

Cross-read of every `deferred` line of
`apps/souscription-simulation-service/parity-checks/*/test-gaps.md` against this audit.
Each line is either **measured here** (with the figure above) or **re-owned** with a
named owner and a due date. None is promoted to "passed".

| Inherited deferral | Origin | Status at this audit | Owner / due date |
| --- | --- | --- | --- |
| ≥ 500 simulations/s/core sustained | `17-…-parity` | **measured** — ~11 300 sim/s/core (NFG-003, `SimulationThroughputTest` CPU-time floor); sustained multi-minute campaign on cluster hardware still to run | service DRI — due 2026-11-30 |
| p50/p95/p99 and payload < 1 KB per direction | `25-…-parity` | **measured** — p50 1.042 / p95 2.068 / p99 3.82 ms, 93 B / 128 B (NFG-001); caller-side leg deferred (§6.1) | service DRI — due 2026-11-30 |
| RSS 120–200 MB | `19-…-parity` | **not comparable here** (no cgroup limit): 51–52 MB resting, 210 MB boot instant (NFG-004) | service DRI — due 2026-11-30 (§6.1) |
| 0 OOMKill over a sustained campaign | `19-…-parity` | **deferred** — requires a 384Mi-limited pod and a sustained load generator; no container runtime here | service DRI — due 2026-11-30 |
| `initialDelaySeconds` = measured startup, startup < 1.2 s | `19-…`, `20-…-parity` | **measured, one miss recorded**: startup 0.814 s / 0.784 s / **1.709 s** (third run on a loaded host, above the 1.2 s line, still inside the published 2 s target of NFG-002). `startup_initial_delay_s: 1` with `startup_period_s: 1` and `startup_failure_threshold: 10` absorbs it; the in-manifest comment still quotes an older 0.533 s figure and must be refreshed to the measured band | service DRI — due 2026-11-30 |
| Rollout with 0 lost request; 2→4 replica near-linear scaling | `20-…-parity` | **single-host equivalents measured** (18 322 requests, 0 lost, drain 1.06 s; 2 and 4 replicas, 0 failed); `kubectl rollout` under load deferred (§6.1) | service DRI — due 2026-11-30 |
| Removal from traffic within one probe interval | `20-…-parity` | **partially measured** — readiness flips DOWN at queue saturation inside the 5 s period; endpoint removal takes `failureThreshold: 3` intervals by design, cluster timing to confirm | service DRI — due 2026-11-30 |
| `promtool check rules` / `test rules` green | `21-…-parity` | **deferred** — `promtool` absent; `deploy/alerts/simulation-alerts-test.yml` exists and is the input of the blocking `alert-rules-test` stage | service DRI — due 2026-11-30 |
| Complete 3-level trace; real OTLP export to a collector | `22-…-parity` | **deferred** — needs the gateway and souscription-service against one collector; only the in-memory exporter is exercised here | service DRI with the gateway and souscription-service owners — due 2026-11-30 |
| Swagger UI 404 under the `prod` profile | `23-…-parity` | **statically verified, live check deferred** — `quarkus.swagger-ui.always-include=false` by default, `true` only under `%dev` and `%test`; `SwaggerUiProfileTest` asserts the test-profile 200 and the property, it does not boot a `prod` JVM | service DRI — due 2026-11-30 |
| TLS-layer rejection without a certificate; unlisted identity 403 | `24-…-parity` | **measured live** — curl exit 56 (no certificate, rejected before any request), 403 `problem+json` for a PKI-valid uncontracted identity (NFG-007/008) | — |
| `testssl.sh` TLS 1.3-only confirmation; unauthorised-pod block by the default-deny NetworkPolicy | `24-…-parity` | **deferred** — no external scanner and no cluster; locally: TLS 1.2 handshake refused (curl exit 35), `kind: Ingress`/`kind: Route` absent from `deploy/`, `ClusterIP` only. Never closed by relaxing a control | service DRI — due 2026-11-30 |
| Rate limit **per calling identity** (two client certificates) | `26-…-parity` | **deferred** — global limiter behaviour measured live (2 661 × 429 / 3 000, 0 × 5xx); per-identity partitioning needs a second issued identity | service DRI — due 2026-11-30 |
| Manifests valid (`kubectl apply --dry-run=client`); resolved-pipeline stages present; pipeline < 5 min | `27-…-parity` | **partially measured** — `.ci/pipeline.yml` is a parameter-only instance of `.ci/templates/quarkus-service-pipeline.yml` (no bespoke stage, NFG-019) and the local gates total ~77 s; `kubectl` dry-run and the resolved-pipeline/CI duration deferred | service DRI — due 2026-11-30 |
| Certificate rotation, log retention, audit-store access control | `15-…`, `24-…`, `27-…-parity` | **platform concerns, no closure claimed** — recorded in the operational surface, not audited here | platform/security owners with the service DRI — due 2026-11-30 |
| Actuarial sign-off on the final-capital reference values | `12-…-parity` | **human decision, open** — the values are byte-compatible with the legacy implementation (`DecimalContractTest`, `SimulationServiceTest`); business sign-off is not an engineering gate | business/actuarial owner with the service DRI — due 2026-11-30 |
| Consumer-side verification against a live provider; decimal fidelity through the gateway | `10-…`, `12-…`, `32-…-parity` | **deferred** — owned by `32-…-parity` and the two consumer applications' own contract-validation stages | gateway and souscription-service owners — due 2026-11-30 |
| 99.9 % availability actually achieved | `31-…-parity` registry | **not verifiable pre-production** — operational objective, not an audited result; the alert rules are the proxy | service DRI, first post-cut-over review — due 2026-12-31 |



## 7. Closure of the eight waves

**94/94 gaps closed.** Wave 7 closes the last five — CFG-011, TG-008, FG-024, FG-025,
FG-026 — on top of the 89 closed by waves 0 to 6 (`MIGRATION_ORCHESTRATION.md`).

**16/16 target components delivered**, the last being `restAssuredTestHarness`:
`SimulationServiceTest` preserved as the behavioural reference, the simulation half of
`InputValidatorTest` extracted, the jsoup HTML assertions replaced by the REST Assured
JSON matrix of `SimulationContractIT`, and the 10 Gherkin scenarios implemented against
`/v1` with the Cucumber CI stage **blocking** (no `allow_failure` in the shared template).

**No test deleted or disabled, in the module or in the monolith:**

```
grep -rn "@Disabled\|@Ignore" souscription-simulation-service/src/test src/test   → no match
git diff --stat src/ features/ global/                                            → empty
```

The monolith's `SimulationServiceTest` and `InputValidatorTest` are untouched — the
subscriber cases stay the property of `souscription-service` and were neither removed
from the monolith nor duplicated here. `jsoup` is absent from the module POM and no
CSRF/session test is duplicated here. The two skips in the 340-test run are
assumption-based (`Assumptions`), not disabled tests.

## 8. Cut-over verdict

Every criterion measurable in this environment passes: the build with the blocking
Cucumber barrier, the published latency budget, the capacity and rate-limit semantics,
the fault injection, the mTLS conformance of the three verdicts, the empty OpenAPI diff
against both frozen contracts, the 88 scenarios all assigned, and 0 CVE of HIGH or
above on the resolved tree. The items of §6.1 and the re-owned lines of §6.2 must be
executed and recorded in the integration/cluster environment by the named owner on or
before the stated date and **before** the cut-over is declared complete; a breach of any
of them blocks the cut-over, and the monolith remains the production reference until it
is cleared.
