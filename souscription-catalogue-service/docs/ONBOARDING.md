# Onboarding — souscription-catalogue-service (W7-T06)

Target skill base: **Quarkus 3.20 LTS (Java 21) + Jakarta REST + CDI**. Everything below is runnable
**from the repository root** and was executed as written on 2026-11-09 (see
`## Walkthrough record`). The module charter, the contracts and the benchmark summary stay in
[`../README.md`](../README.md); this document is the day-one guide.

Prerequisites: a JDK 21 (`java -version` → `openjdk 21.x`) and Maven 3.9+. No application server, no
Cargo, no Docker needed for the five loops below.

---

## 1. Dev mode and the live-reload loop

```bash
mvn -f souscription-catalogue-service/pom.xml quarkus:dev
```

Starts the service in the `dev` profile: **cleartext HTTP on 8443** (`quarkus.http.port=8443` with
`%dev.quarkus.http.insecure-requests=enabled`,
`%dev.quarkus.http.ssl.client-auth=none`, so no client certificate is needed), management interface
on 9000, human-readable (non-JSON) console logs (`%dev.quarkus.log.console.json=false`). Add
`-Dquarkus.http.port=8099` if 8443 is taken.

```bash
curl -s http://localhost:8443/v1/products | head        # 3 products
curl -s http://localhost:8443/v1/products/PRUDENT       # one product sheet
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8443/v1/products/INCONNU   # 404
```

The loop: **edit a `src/main/java` file, save, re-issue the request.** Quarkus recompiles and
restarts on the next HTTP request — there is no build, no repackage and no deploy step. The console
prints `Restarting quarkus due to changes in …` then `Live reload total time: …`.

Measured here (three consecutive reloads of `ProductResource.java`, from `/tmp` dev-mode log):

| Iteration | `Live reload total time` |
|---|---|
| 1 (cold caches) | 0.779 s |
| 2 | 0.366 s |
| 3 | 0.406 s |

Median **0.406 s**, worst **0.779 s** — inside the **< 2 s per iteration** requirement. Cold start of
dev mode itself was 1.308 s. Re-measure the same way after any dependency addition:

```bash
# in another shell, while quarkus:dev runs
touch souscription-catalogue-service/src/main/java/fr/demo/souscription/catalogue/api/ProductResource.java
curl -s -o /dev/null http://localhost:8443/v1/products   # triggers the reload
# then read "Live reload total time" in the dev-mode console
```

Other dev-mode keys: `r` runs the tests continuously, `h` lists the shortcuts, `q` quits. The Dev UI
is at `http://localhost:8443/q/dev`.

## 2. Running the tests

```bash
mvn -f souscription-catalogue-service/pom.xml clean test     # unit + @QuarkusTest layers (90 tests)
```

```bash
mvn -f souscription-catalogue-service/pom.xml clean verify -Pcucumber   # THE build gate
```

`clean verify -Pcucumber` is the **promoted gate** (W7-T02) and the command the CI `test` job runs:
it adds the behavioural suite on top of the unit and `@QuarkusTest` classes. Last run here:
90 tests, `13 Scenarios (13 passed)`, `47 Steps (47 passed)`, `BUILD SUCCESS` in 14 s.
`clean test` alone does **not** run Cucumber (`CatalogueCucumberTest` is bound to failsafe by the
profile), so never use it as the gate.

The six test layers and which class belongs to which are listed in `../README.md`
(`## Test pyramid (W7-T04)`). Replaying the behavioural suite against a deployed mTLS instance:

```bash
mvn -f souscription-catalogue-service/pom.xml verify -Pcucumber \
  -Dcatalogue.base-uri=https://host:8443 \
  -Dcatalogue.client-cert=client.crt -Dcatalogue.client-key=client.key
```

## 3. Reading the contract

| What | Where |
|---|---|
| Live document | `GET /q/openapi` (`http://localhost:8443/q/openapi`, returns 200 in dev) |
| Frozen copy under version control | `souscription-catalogue-service/src/main/resources/META-INF/openapi-contract-v1.yaml` |
| Regression guard | `OpenApiContractTest` — fails the build if the two differ |

```bash
curl -s http://localhost:8443/q/openapi                                            # served document
curl -s -H 'Accept: application/json' http://localhost:8443/q/openapi | head -20   # JSON flavour
cat souscription-catalogue-service/src/main/resources/META-INF/openapi-contract-v1.yaml
```

The two must stay byte-identical. **Any intentional endpoint or DTO change requires updating the
frozen file in the same commit**, and it is reviewed as a contract change: `souscription-gateway`
and `souscription-service` generate their clients from it. Operation ids (`list`, `byCode`) and the
absence of a `servers` block are frozen on purpose — see `../README.md`
(`## OpenAPI contract (W2-T05, W2-T06)`).

## 4. Observability

Metrics, health and logs do **not** sit on the business port; they are on the **management port
9000** (`quarkus.management.port=9000`), which is outside the mTLS perimeter and cluster-internal.

```bash
curl -s -o /dev/null http://localhost:8443/v1/products                 # first, exercise the endpoint
curl -s http://localhost:9000/q/metrics | grep '^catalogue_products'   # the two @Timed timers
curl -s http://localhost:9000/q/health/ready                           # {"status":"UP", … products:3}
curl -s http://localhost:9000/q/health/live
```

Verified here in dev mode: `/q/openapi` → 200, `:9000/q/metrics` → 200,
`:9000/q/health/ready` → 200 with `catalogue-seed UP`, `data.products = 3`.

| Signal | How to reach it |
|---|---|
| **Metrics** | `:9000/q/metrics` (Prometheus text). In a cluster: scraped every 30 s via `deploy/servicemonitor.yaml` → Prometheus → the Grafana *Catalogue service* dashboard. Alerts in `deploy/alerts.yaml`. |
| **Traces** | OTLP/gRPC to `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://otel-collector:4317`), sampler `parentbased_traceidratio`. Query the trace store by `service.name=souscription-catalogue-service`. Locally, test the propagation with: `curl -s -H "traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01" http://localhost:8443/v1/products` |
| **Logs** | one **JSON record per line on stdout** (`quarkus.log.console.json=true`), carrying `traceId`/`spanId`, `service.name`, `service.version`; tailed by the platform log agent. The application writes no log file. In `%dev` the JSON layout is off for readability — to see the production format: `mvn -f souscription-catalogue-service/pom.xml package -DskipTests && java -jar souscription-catalogue-service/target/quarkus-app/quarkus-run.jar` (set the `TLS_*` variables, or use `-Dquarkus.profile=test` for a cleartext run). |

`traceId` is the join key between a log line and its trace. Readiness consults no remote dependency,
so a third party can never trigger a restart loop.

## 5. Changing the catalogue

The product data are **static, immutable and compiled in**: there is no datastore and no admin
screen, so a catalogue change is a **code change plus a redeployment**. Procedure, in one commit:

1. Edit `src/main/java/fr/demo/souscription/catalogue/service/CatalogueSeedProvider.java`
   (product code, name, profile, description, annual rate).
2. Update the golden file **in the same change**:
   `src/test/resources/golden/products-v1.json` — UTF-8, exact field order, rates at six decimals
   (`0.020000`, not `0.02`). `CatalogueGoldenFileTest` compares `GET /v1/products` byte for byte, so
   a drift in scale, encoding or field order fails the build.
3. If a DTO or endpoint shape changed too, regenerate the frozen contract
   (`src/main/resources/META-INF/openapi-contract-v1.yaml`) — see section 3.
4. Run the gate: `mvn -f souscription-catalogue-service/pom.xml clean verify -Pcucumber`.
5. **Get the rate reviewed.** The annual rate is an auditable contractual figure (CFG-011): the
   golden-file diff is the artefact the reviewer signs off, and the start-up SHA-256 fingerprint
   logged at `INFO` (`Catalogue Horizon amorce avec 3 produits, empreinte SHA-256 …`, also served as
   the HTTP `ETag`) changes with it. A changed fingerprint in the deployment log is the proof the new
   data is live.
6. Redeploy — nothing else propagates the change:
   ```bash
   mvn -f souscription-catalogue-service/pom.xml package -Dquarkus.container-image.build=true
   kubectl apply -f souscription-catalogue-service/deploy/
   kubectl rollout status deployment/souscription-catalogue-service
   ```
7. Callers cache `/v1` for `max-age=300` (default). A change becomes visible within five minutes;
   there is **no purge mechanism**. If it must be visible immediately, lower
   `CATALOGUE_CACHE_MAX_AGE` ahead of the release.

Never add a write path: the per-instance in-memory model is only correct because the catalogue is
immutable (see `../README.md`, *Per-instance in-memory catalogue*). Never add a blocking call to
`ProductResource` or `CatalogService` without moving the method to `@Blocking` in the same change.

---

## What disappears from the team's daily work

Struts 1 has been end of life since 2013. None of the following exists in this module any more —
0 end-of-life technology remains in scope (NFG-017, verified by the final audit: `dependency:tree |
grep -ci struts` = 0, no JSP/JSTL artefact, `maven.compiler.release=21`).

| Retired | What replaces it |
|---|---|
| `struts-config.xml` (action mappings, form beans, forwards) | nothing to configure: `@Path` / `@GET` annotations on `ProductResource`. 2 XML files and 7 XML configuration points → **0** |
| `ActionForward` / `ActionMapping` (`mapping.findForward("catalogue")`) | the method's return value *is* the response; status and headers via `Response` / exception mappers (`ProductNotFoundMapper`, `MethodNotAllowedMapper`) |
| `ActionForm` and its `validate()` | records as DTOs (`ProductSummary`, `ProductDetail`) + Hibernate Validator annotations; JSON (de)serialisation is automatic |
| `Action` classes extending the Struts base class (`CatalogAction`) | plain CDI beans — `@ApplicationScoped`, `@Inject`, no framework inheritance |
| JSP (`catalogue.jsp`, `produit.jsp`) and JSTL tag libraries | no server-side view layer at all: this service returns JSON. The screens belong to `souscription-web` (Angular 21) |
| `web.xml` (`session-config`, `welcome-file-list`, `error-page`, `jsp-config`) and **every XML deployment descriptor** | `src/main/resources/application.properties`, every adjustable value as `${VAR:default}` |
| The HTTP session (20-minute timeout, session cookie, CSRF session attribute) | stateless service; the subscription draft is a persisted table owned by `souscription-service`, the CSRF double-submit cookie belongs to `souscription-gateway` |
| The **Cargo loop** — download a Tomcat, provision it, `cargo:deploy`, `cargo:redeploy`, restart to see a change | `quarkus:dev` live reload (measured 0.37-0.78 s above) and, for a real run, a self-contained fast-jar: `java -jar target/quarkus-app/quarkus-run.jar` |
| `*.do` URLs, server-rendered error pages, `response.sendError(405)` | `/v1/products`, `/v1/products/{code}`; RFC 7807 `application/problem+json` bodies; 405 answered natively by Quarkus REST |
| jsoup-based HTML assertions in the tests | REST Assured + JSON-schema validation against the frozen contract |
| Java 8 | Java 21 LTS (records, `List.copyOf`, pattern matching) |

Day-to-day consequence: a change is **one Java file plus one test**, not a Java file plus two XML
descriptors plus a JSP plus a container restart.

---

## Walkthrough record

NFG-017 requires this document to be walked through by someone who has never worked on the module,
with every quoted command executed from the repository root.

| Item | Record |
|---|---|
| Date of the walkthrough | **2026-11-09** |
| Reviewer | **Amazon Q transformation agent (W7-T06)** — acting as the first-time reader: no prior knowledge of the module's internals was used, every command below was executed as written from the repository root and its output compared with this document |
| Commands executed and their result | `mvn … clean verify -Pcucumber` → `BUILD SUCCESS`, 90 tests + 13 scenarios / 47 steps · `mvn … quarkus:dev` → `started in 1.308s. Listening on: http://localhost:8443`, management on 9000 · `curl :8443/v1/products` → 3 products, `/v1/products/PRUDENT` → product sheet, `/v1/products/INCONNU` → 404 · 3 × live reload → 0.779 / 0.366 / 0.406 s (`Live reload total time`) · `curl :8443/q/openapi` → 200, `/q/dev` → 302 (redirect to the Dev UI) · `curl :9000/q/metrics | grep '^catalogue_products'` → 15 lines after a first `/v1/products` call · `curl :9000/q/health/ready` → 200, `catalogue-seed UP`, `products: 3` |
| Result | **PASS after one correction** — all five topics (dev mode, tests, contract, observability, catalogue change) are reproducible. Two commands had to be amended during the walkthrough: the dev-mode URLs quoted port **8080** while the `dev` profile listens on **8443** (`quarkus.http.port=8443`, no `%dev` override), and the `/q/metrics` grep returned nothing on a freshly started instance. Both are fixed in this document (8443 everywhere, plus a priming `/v1/products` call before the grep) and re-verified as written |
| Independent replay (parity check 36) | **2026-11-09** — every fenced command re-executed from the repository root: `clean verify -Pcucumber` → 90 tests, 13 scenarios / 47 steps, `BUILD SUCCESS` · `clean test` → 90 tests · `package -DskipTests` → `target/quarkus-app/quarkus-run.jar` · `quarkus:dev` → started in 1.131 s on 8443, management 9000 · `/v1/products` 3 products, `/v1/products/PRUDENT` sheet, `/v1/products/INCONNU` 404, `/q/openapi` 200 (YAML and JSON), `/q/dev` 302, `traceparent` propagation 200 · `:9000/q/metrics | grep '^catalogue_products'` 15 lines after priming, `:9000/q/health/ready` 200 `catalogue-seed UP products:3`, `/q/health/live` 200 · 3 × live reload 0.557 / 0.367 / 0.421 s (< 2 s, FG-020). One documentation correction applied: the retired-technologies row quoted a stale live-reload range (`0.41-1.01 s`), now `0.37-0.78 s` to match the measurement table. Module sources and `pom.xml` unchanged |
| Residual gap | the walkthrough by a **human** developer outside the module team is **not** recorded: no such reviewer is reachable from this build environment. It is tracked with the other deferred, environment-blocked checks in [`../test-gaps.md`](../test-gaps.md) and must be signed off in this table (name + date) before cut-over. The command-by-command verification above stands as the executable part of the check |
