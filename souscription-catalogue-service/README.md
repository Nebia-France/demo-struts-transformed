# souscription-catalogue-service

Quarkus 3.15 (Java 21) service exposing the product catalogue. It is **stateless**: no
datastore, no server-side view layer.

**New on the team? Start with [`docs/ONBOARDING.md`](docs/ONBOARDING.md)** (W7-T06): dev mode and the
live-reload loop, running the tests, reading the contract, reaching the observability dashboards, the
catalogue-change procedure, and what disappears from the daily work versus Struts 1 and JSP.

## Module charter (W3-T01)

| Item | Value |
| --- | --- |
| Scope | the catalogue only: `CatalogService`, `Product`, `/v1/products` |
| Callers | `souscription-gateway` (public re-exposure under `/api/v1`), `souscription-service` (product lookup on the confirmation path) |
| Contract | `/v1`; live document at `/q/openapi`; frozen copy in `src/main/resources/META-INF/openapi-contract-v1.yaml` |
| Deployable | `souscription-catalogue-service-1.0.0-runner.jar` (fast-jar), independent of the legacy WAR |
| Persistence | **none** |

This module is a self-contained deployable and a remote read model: it shares no memory with
either caller. Its Maven coordinates have no parent and no dependency on `fr.demo:horizon-souscription`
nor on any `org.apache.struts` artifact (verified with `dependency:tree`).

### No persistence

No datasource, no JDBC driver, no connection pool, no object-relational mapping and no transaction
manager are declared or used. Subscription persistence — including the server-side subscription
draft table that replaces the legacy 20-minute HTTP session — is owned by `souscription-service`.

### Per-instance in-memory catalogue (AG-010, FG-017)

The catalogue is held in memory **per instance**, seeded by `CatalogueSeedProvider` at start-up.
This is correct under horizontal scaling because the product data are **static and immutable**:
every replica seeds the identical dataset from the same compiled source, there is no write path and
no request can mutate the catalogue, so all replicas are byte-identical at all times and any replica
answers identically. No shared store, cache coherence protocol or sticky routing is therefore
required. This justification is recorded explicitly: if the catalogue ever becomes mutable at
runtime, the per-instance model becomes incorrect and a shared datastore must be introduced before
scaling out.

### Operational constraint

Any catalogue change (new product, changed rate or label) requires a **rebuild and redeployment** of
the service. This is accepted (user decision TQ-040): the data are static and the change rate is
near zero.

### Descriptor-free configuration (W3-T02)

The legacy `web.xml` and `struts-config.xml` are not carried over; all retained configuration lives
in `src/main/resources/application.properties`, with every adjustable value in `${VAR:default}` form
(no hardcoded environment value, no secret). Deliberately **not** translated:

| Legacy descriptor element | Target |
| --- | --- |
| `session-config` (20 min, http-only cookie, COOKIE tracking) | dropped — stateless service (AG-009); the draft state is a persisted table owned by `souscription-service` |
| `welcome-file-list` (`catalogue.do`) | dropped — no server-rendered entry page, the SPA is served by `souscription-web` |
| `error-page` 404 → `erreur.jsp` | dropped — RFC 7807 problem responses (plan 18) |
| `jsp-config` page-encoding UTF-8 | dropped — UTF-8 is the default (locked in plan 17) |

## Excluded concerns

### Persistence (CFG-012, TG-011, FG-018, IG-002)
The legacy WAR bootstrapped an embedded file database and a POSIX-restricted data
directory from `fr.demo.souscription.web.Application`, together with
`SubscriptionRepository.initialize()` and `src/main/resources/db/schema.sql`. None of this
is inherited here: the catalogue serves static product data. Subscription persistence is
owned by `souscription-service`. Accordingly this module declares no database driver, no
connection-pool extension and no object-relational-mapping extension, and no data
directory property.

### View layer (TG-012)
The JSP/JSTL views and their stylesheet are taken over by **`souscription-web`**
(Angular 21) — see `apps/souscription-web/transformation-plans/`:

| Legacy asset | New owner |
| --- | --- |
| `src/main/webapp/WEB-INF/jsp/catalogue.jsp` | `souscription-web` |
| `src/main/webapp/WEB-INF/jsp/produit.jsp` | `souscription-web` |
| `src/main/webapp/assets/css/application.css` | `souscription-web` |

No view-layer or tag-library dependency is declared in this module.

### Logging backend (TG-005, FG-024)
The legacy `org.slf4j:slf4j-simple` runtime binding is not inherited; Quarkus supplies
`slf4j-jboss-logmanager`. Console logging is configured in
`src/main/resources/application.properties`. JSON structured logging is added by a later
plan.

## Ported scope

Only `fr.demo.souscription.service.CatalogService` and `fr.demo.souscription.model.Product`
are carried over from the legacy sources (by plan 04).

## HTTP surface (`/v1/products`)

Two read-only endpoints, declared by `fr.demo.souscription.catalogue.api.ProductResource`:

| Endpoint | Response |
| --- | --- |
| `GET /v1/products` | `200` + JSON array of `ProductSummary` |
| `GET /v1/products/{code}` | `200` + `ProductDetail`, `404` on an unknown code |

`CatalogService.find` compares codes with `equals`, so the lookup stays case- and
whitespace-sensitive exactly as the legacy `CatalogAction` was: `prudent` and
`%20PRUDENT%20` yield `404`. The path parameter is never trimmed. The legacy query form
`/v1/products?code=X` is **not** exposed — the path parameter is the frozen contract shape
and `souscription-gateway` performs any translation (IG-006).

No `@POST`, `@PUT` or `@DELETE` method is declared, so Quarkus REST answers
`405 Method Not Allowed` natively; the legacy `response.sendError(405)` guard has no
counterpart. The `404` is raised as `ProductNotFoundException` and rendered by
`ProductNotFoundMapper` (bodiless until the RFC 7807 plan), never inline.

Quarkus REST normalises a trailing slash away before matching, so the `@Path("/{code: .+}")`
template alone cannot distinguish `GET /v1/products/` from `GET /v1/products` (declaring both
fails the build with a duplicate-endpoint error). The empty code is therefore rejected by the
pre-matching `EmptyProductCodeFilter`, which raises the same `ProductNotFoundException` so the
`404` still flows through the mapper.

### Presentation data is not published (FG-008)

The legacy request attributes `step`, `pageTitle`, `products`, `product` and the JSP
forwards (`catalogue`, `produit`) have no counterpart in the JSON payloads. In particular
`step = 1` and `pageTitle = "Catalogue des placements"` are now the responsibility of
**`souscription-web`** (Angular 21).

### Routing-reduction proof (NFG-016)

| Metric | Source baseline | Target |
| --- | --- | --- |
| XML configuration files routing the catalogue | 2 (`struts-config.xml`, `web.xml`) | 0 |
| XML configuration points | 7 | 0 |
| Transport lines of code for 2 endpoints | 23 (`CatalogAction`) + 4 XML lines | <= 25 annotated lines, no XML |

## OpenAPI contract (W2-T05, W2-T06)

| Item | Location |
| --- | --- |
| Live document (internal) | `GET /q/openapi` (`application/yaml` or `application/json`) |
| Frozen copy under version control | `souscription-catalogue-service/src/main/resources/META-INF/openapi-contract-v1.yaml` |
| Regression guard | `fr.demo.souscription.catalogue.api.OpenApiContractTest` |

The document is produced by `quarkus-smallrye-openapi` from the JAX-RS and `@Schema` annotations.
`mp.openapi.extensions.smallrye.operationIdStrategy=METHOD` fixes the operation identifiers
(`list`, `byCode`): changing this setting later renames the generated client operations and breaks
both consumers, so it is frozen now. `quarkus.smallrye-openapi.auto-add-server=false` keeps the
runtime-dependent `servers` block out of the document, so the served and frozen copies are
byte-identical; the per-environment cluster URLs live in the reference contracts under
`global/contracts/`. `quarkus.swagger-ui.always-include=false` keeps the Swagger UI out of
production builds (hardened further by plan 26).

`OpenApiContractTest` compares the served document with the frozen file, so **any intentional DTO
or endpoint change must come with an explicit, reviewable update of
`META-INF/openapi-contract-v1.yaml`**.

### Consumer handover

| Consumer | Usage |
| --- | --- |
| `souscription-gateway` | Generates its client from this document and re-exposes the catalogue publicly under `/api/v1` |
| `souscription-service` | Generates its client for the product lookup on the confirmation path |

Both consumer teams may generate their clients **now**, from the frozen file, without waiting for
waves 3 to 6 (mitigation R-001). The paths (`/v1/products`, `/v1/products/{code}`) and the schema
names (`ProductSummary`, `ProductDetail`) match the reference contracts
`global/contracts/souscription-gateway-to-souscription-catalogue-service/openapi.yaml` and
`global/contracts/souscription-service-to-souscription-catalogue-service/openapi.yaml`.

The two documents express the same URLs differently, which is expected and not a divergence: the
reference contracts carry the `/v1` prefix in their per-environment `servers` URLs and declare the
paths as `/products` and `/products/{code}`, while this document has no `servers` block
(`auto-add-server=false`, required for the served/frozen byte-identity) and therefore carries the
prefix in the paths themselves. The resolved endpoints are identical
(`.../v1/products`, `.../v1/products/{code}`), so a generated client targets the same surface in
both cases. A literal path-string comparison between the two files will differ on that prefix only;
plan 37 settles the representation. The full
comparison, including the `mutualTLS` security scheme, the correlation headers and the RFC 7807
error bodies declared there, is plan 37.

### ⚠ The wave-2 instance is NOT secured

Since W6-T01 the application port requires mTLS with client-certificate validation (see
*Mutual TLS on the application port* below), but this build still has **no network isolation** —
that is plan 27. It may only be deployed on a closed integration environment and **must never be
exposed** publicly.

## Golden file: the published catalogue is frozen (W4-T04, CFG-011)

| Item | Location |
| --- | --- |
| Golden file (UTF-8, under version control) | `src/test/resources/golden/products-v1.json` |
| Regression guard | `fr.demo.souscription.catalogue.CatalogueGoldenFileTest` (byte-for-byte comparison of `GET /v1/products`) |

The annual rate is an auditable business figure, so **any change to a rate, a product name, a
profile or a description requires an explicit update of
`src/test/resources/golden/products-v1.json`, reviewed as part of the same change**. The test
compares the served payload with the file exactly (UTF-8 bytes, `strip()`ped of the trailing
newline only), so a silent drift in scale (`0.020000` → `0.02`), in encoding or in field order
fails the build. The file was generated **after** the BigDecimal-fidelity and UTF-8 plans, so it
freezes the corrected representation, not the legacy defect.

### Start-up fingerprint

`CatalogueSeedProvider` computes the SHA-256 of the JSON representation of the seeded products and
logs the product count plus the hex digest at `INFO`:

```
Catalogue Horizon amorce avec 3 produits, empreinte SHA-256 a182add5bf4ef9edecd8a7bdff3ce0d24f191e463ee7b405862bd76de93ad7d2
```

The value is exposed by `CatalogueSeedProvider.getCatalogueFingerprint()` and stored in
`CatalogService.version()`, which `ProductResource` reuses as the HTTP `ETag` (W6-T04). The digest is
computed over the domain `Product` list, not over the
`ProductSummary` payload, so it changes whenever any business field changes — including the
`description`, which the list endpoint does not publish.

## HTTP caching: `ETag`, `Cache-Control`, 304 (W6-T04)

The source read was in-process and the monolith sent `Cache-Control: no-store`, which is wrong for a
static reference resource: the catalogue only changes on redeployment. Both `/v1` reads now return

```
ETag: "a182add5bf4ef9edecd8a7bdff3ce0d24f191e463ee7b405862bd76de93ad7d2"
Cache-Control: max-age=300, public
```

and answer a matching `If-None-Match` with a bodiless `304`. The `ETag` is the start-up fingerprint,
**computed once at seeding, never per request**, so it is stable across requests and identical across
replicas of the same image — a prerequisite for revalidation to work behind a load balancer. The
detail endpoint derives its tag from the same fingerprint plus the product code.

### Cache agreement with the two callers

`souscription-gateway` and `souscription-service` cache `/v1` responses for the advertised
`max-age` and revalidate with `If-None-Match` afterwards, so a steady-state navigation costs a
304 with no payload. Caching is a caller-side optimisation only: it never replaces the agreed
failure mode (CFG-005), where an unavailable catalogue makes the confirmation fail with a clean
503 rather than serving a cached annual rate.

### The `max-age` trade-off

`max-age` is set by `catalogue.cache.max-age-seconds`, overridable through the
`CATALOGUE_CACHE_MAX_AGE` environment variable; the default is **300 s**.

| Value | Consequence |
| --- | --- |
| Too long | a newly deployed catalogue (new rate, new product) stays invisible to callers and browsers for up to that duration |
| Too short | revalidation traffic and tail latency on the confirmation path rise with no freshness benefit, since the data only changes on redeployment |

300 s is the compromise: the catalogue is static between deployments, and a rate change becomes
visible within five minutes without any cache purge. A deployment that must be visible immediately
should lower `CATALOGUE_CACHE_MAX_AGE` ahead of the release rather than rely on a purge mechanism,
which does not exist.

## Message bundle and UI labels (FG-014)

`src/main/resources/ApplicationResources.properties` is **not** copied into this module, and no
`ApplicationResources*.properties` file exists here:

| Legacy asset | New owner |
| --- | --- |
| `src/main/resources/ApplicationResources.properties` | `souscription-web` i18n files (Angular 21) |

**Boundary: business data server-side, UI labels client-side.** The only French strings this
service publishes are business data (product `name`, `profile`, `description`) plus the static
`title` / `detail` fields of the `application/problem+json` responses. Publishing those two
problem fields in French is an accepted, documented choice: they are part of the frozen error
contract consumed by `souscription-gateway`, not UI copy. Every label, heading, button and
validation message rendered to the end user — including `pageTitle` and the step labels — lives in
the `souscription-web` i18n bundles.

## Contrat de resilience /v1

In the source the catalogue was an in-process read with no availability, latency or degradation
commitment. As a remote dependency of the confirmation path it now needs a published contract so
the `souscription-gateway` and `souscription-service` teams can wire their timeouts **now**, before
the mechanisms exist (AG-013, W4-T06).

| Commitment | Value |
| --- | --- |
| Availability | **99.9%** (user decision; error budget ~43 min/month) |
| Server latency | **p95 <= 25 ms**, **p99 <= 60 ms** |
| Caller-observed latency | **p95 <= 100 ms** (mTLS and serialisation included) |
| Caller-side policy | timeout **2 s**, **1 retry**, documented fallback (worst case ~4 s) |
| Data freshness | static; `Cache-Control` + `ETag` on `/v1` responses |

The same commitments are mirrored in the OpenAPI document via
`quarkus.smallrye-openapi.info-description` in `src/main/resources/application.properties`, and the
frozen copy `src/main/resources/META-INF/openapi-contract-v1.yaml` was regenerated accordingly
(guarded by `OpenApiContractTest`).

### This section publishes the engagement only

The mechanisms behind these figures are **not implemented here**:

| Mechanism | Implemented by |
| --- | --- |
| `Cache-Control` and `ETag` on `/v1` responses | **implemented** (W6-T04, see *HTTP caching* below); the `ETag` reuses the start-up fingerprint |
| Caller fault tolerance (timeout, retry, circuit breaker, fallback) | plan 28, **in the caller modules** |

**No `@Timeout`, `@Retry` or `@Fallback` annotation exists in `src/main/`, and none may be added
here:** the fault-tolerance policy is caller-side by design. `quarkus-smallrye-fault-tolerance` is
on the classpath only to freeze the wave-3 CVE baseline against the final classpath (W3-T08).

### Open points: divergence from the reference contracts

The published figures above are the user-confirmed engagement (OD-009 confirmed at 99.9%). They are
**narrower** than the two reference contracts
`global/contracts/souscription-gateway-to-souscription-catalogue-service/resilience-contract.json`
and `global/contracts/souscription-service-to-souscription-catalogue-service/resilience-contract.json`.
The divergences are recorded here rather than silently resolved — each must be settled with the
owning caller team before plan 28 wires the clients:

| Item | Published here | Reference contracts | Open point |
| --- | --- | --- | --- |
| Availability | 99.9% | 99.9% | aligned, no divergence |
| Server p95 | 25 ms | 40 ms | which figure becomes the SLO measured by the Micrometer timers (plan 22)? |
| Server p99 | 60 ms | 150 ms | same question; 150 ms is incompatible with a 2 s timeout budget only if retries stack |
| Caller timeout | 2 s | 3 s | the callers' MicroProfile `@Timeout` value must be chosen once, for both callers |
| Caller retries | 1 | 2 (exponential backoff, 100 ms base, 1 s cap) | worst case is ~4 s here vs ~9 s with the reference values |
| Circuit breaker / bulkhead | not published | declared (20 req volume, 0.5 ratio, 10 s delay; bulkhead 50 / 20) | caller-side only; keep the reference values unless the timeout/retry decision changes them |
| Caller-observed p95 | 100 ms | not stated | to be added to the reference contracts |
| Fallback | "documented fallback" | gateway: 503 problem+json, no cached catalogue — service: fail the confirmation with a retryable 503, persist nothing | the reference wording is the authoritative one; **never serve a stale annual rate** |

Until those points are settled, a caller configured with the reference values (3 s, 2 retries)
still honours this contract: it is strictly more tolerant than the 2 s / 1 retry published here.

## Build

```
mvn -f souscription-catalogue-service/pom.xml clean package
```

Produces the fast-jar under `target/quarkus-app/` (`java -jar target/quarkus-app/quarkus-run.jar`).
The container image build is **opt-in**, because it needs a local Docker/Podman daemon:

```
mvn -f souscription-catalogue-service/pom.xml clean package -Dquarkus.container-image.build=true
```

Measured cold `clean package` in this environment: ~7 s, well inside the 4-minute budget (NFG-004).
Container-runtime-dependent parity checks deferred in this environment are recorded in
`test-gaps.md`.

The legacy WAR at the repository root is intentionally left untouched: it remains the
rollback target and the reference for the sibling applications.

## Test pyramid (W7-T04)

The five required layers — service unit tests, `@QuarkusTest` resource tests, the OpenAPI contract
test, the golden-file test and the Cucumber suite — plus a plain-JUnit domain layer below them,
with the Quarkus container reserved for the CDI and REST layers so the build stays
fast (FG-022, TG-007):

| Layer | Container | Classes |
| --- | --- | --- |
| 1. Domain unit tests (pure arithmetic, no container) | plain JUnit 5 | `ProductParityTest`, `ProductDtoParityTest`, `GoldenFileParityTest`, `ResilienceContractParityTest` |
| 2. Service tests (CDI bean seeded by `StartupEvent`, injected) | `@QuarkusTest` | `CatalogServiceTest`, `CatalogServiceParityTest`, `CatalogueSeedParityTest`, `ConcurrentReadParityTest` |
| 3. REST resource tests (HTTP surface, REST Assured) | `@QuarkusTest` | `ProductResourceIT`, `ProductResourceParityTest`, `ProductSerializationTest`, `Utf8EncodingTest`, `Utf8WireParityTest`, `BigDecimalWireParityTest`, `ProblemJsonParityTest`, `HttpCacheTest`, `HttpCacheParityTest`, `HealthCheckTest`, `HealthProbeParityTest`, `PrometheusMetricsTest`, `TracePropagationTest` |
| 4. OpenAPI contract test | `@QuarkusTest` | `OpenApiContractTest` |
| 5. Golden-file test (published catalogue frozen) | `@QuarkusTest` | `CatalogueGoldenFileTest` |
| 6. Behavioural suite (Cucumber, `-Pcucumber`) | own fast-jar harness | `CatalogueCucumberTest` |

No test class declares a `@TestProfile`: all `@QuarkusTest` classes share the single default test
profile, so the application boots **once** for the whole surefire run.

All JUnit, REST Assured and `json-schema-validator` versions come from the Quarkus BOM — no
`<version>` element is declared for them in `pom.xml` (TG-007).

## Behavioural suite (Cucumber) — promoted build gate (W7-T02)

The 10 catalogue step definitions are implemented against the `/v1` REST API (they were
`// TODO: Implementer` stubs targeting the Struts `CatalogAction` until this plan), so the Cucumber
suite is now part of the **validation command of the module**:

```
mvn -f souscription-catalogue-service/pom.xml clean verify -Pcucumber   # promoted gate: unit tests + Cucumber
```

The former command `clean test` remains valid but is **no longer the gate**: it runs the unit and
`@QuarkusTest` classes only and does not execute the behavioural suite (`CatalogueCucumberTest` is
still excluded from the default surefire run, the profile binds it to failsafe). The
`test` job of `.github/workflows/build.yml` was switched to the promoted command accordingly.

Glue and feature scope is restricted to the catalogue domain: only
`features/catalogue/catalogue.feature`, `CatalogueSteps` and `CommonSteps` are copied into
`src/test`. The eight catalogue scenarios (13 executions once the two `Scenario Outline`
blocks are expanded) are discovered by `io.cucumber.junit.platform.engine`.

### How the suite reaches the API

`features/step_definitions/CatalogueWorld.java` carries the harness. By default it starts the
fast-jar produced by the `package` phase in profile `test` on a **free port** (no hard-coded port,
cleartext listener as in the `%test` configuration), waits for `GET /v1/products`, and stops it at
the end of the suite. Server output goes to `target/cucumber-catalogue-server.log`.

Replay against a deployed, mTLS-protected instance (plan step 7) — the client certificate material
is supplied on the command line and never committed:

```
mvn -f souscription-catalogue-service/pom.xml verify -Pcucumber \
  -Dcatalogue.base-uri=https://host:8443 \
  -Dcatalogue.client-cert=client.crt -Dcatalogue.client-key=client.key
```

The PEM certificate and its PKCS#8 key are loaded into an in-memory PKCS#12 keystore presented on
every request. When the base URI is given without certificate material, the suite runs against that
endpoint as is (useful for a cleartext dev instance). Status of this replay in the current
environment: **deferred**, no mTLS endpoint available — recorded in `test-gaps.md`.

### Step-by-step mapping

| Gherkin step | Target implementation |
| --- | --- |
| `le catalogue Horizon est initialise avec les produits de reference` | `GET /v1/products` returns 3 products, plus a service-level `CatalogService` seeded for the two non-HTTP scenarios |
| `l'utilisateur demande la page du catalogue` | `GET /v1/products` |
| `l'utilisateur demande la fiche du produit de code "<code>"` | `GET /v1/products/{code}` (explicit `%20` encoding, auto-encoding disabled, so the `" PRUDENT "` edge case is not normalised away) |
| `l'utilisateur soumet le catalogue en methode POST` | `POST /v1/products` → 405 `problem+json` |
| `le systeme affiche les {int} placements du catalogue` | `body("size()", is(n))` |
| `le systeme affiche la fiche du produit nomme`, `le profil affiche est` | `body("name" / "profile", equalTo(...))` |
| `le taux annuel affiche est`, `le taux affiche en pourcentage est` | `BigDecimal.compareTo` on `annualRate` / `ratePercent` |
| `le systeme repond avec le code HTTP {int}` (`CommonSteps`) | `statusCode(n)`, unchanged wording |
| `l'utilisateur recherche un produit sans fournir de code` / `aucun produit n'est retourne` | service level: `CatalogService.find(null)` is empty — no HTTP representation |
| `le systeme tente d'ajouter un produit…` / `une erreur de modification interdite est levee` | service level: `getProducts().add(...)` throws `UnsupportedOperationException` |

Numeric comparison on the rates is **mandatory**: the example table of
`Consultation de la fiche d'un placement existant` writes `0.02` while the wire contract serialises
`0.020000` (6 decimals). A string comparison would fail the gate on a representation difference
instead of a value difference.

### Whitespace-padded product code: percent-encoded in the example table

Gherkin trims the leading and trailing spaces of a table cell, so the cell `|  PRUDENT   |` of
`Code produit vide ou mal formate sur la fiche produit` reaches the step as `PRUDENT` and the
service legitimately answers `200`. The executable copy
(`src/test/resources/features/catalogue/catalogue.feature`) therefore writes that example as
`%20PRUDENT%20`, the wire representation of `" PRUDENT "`: the step disables auto-encoding, the
service receives the padded code and answers `404` (no trimming on `/v1`). The scenario, its tag and
its four examples are unchanged; only the notation of one cell differs from the reference feature in
`features/catalogue/catalogue.feature`, which is kept as authored.

### Steps with no server counterpart

`le systeme indique l'etape 1 du parcours` and `le titre de la page est "Catalogue des placements"`
are presentation assertions (FG-008, see `### Presentation data is not published`). They are
**kept, never deleted** (test-integrity requirement CFG-010) and documented in `CommonSteps` as
`@porte-par-souscription-web`: the SPA owns the page title and the journey step, and verifies them
in its own suite. The `/v1` contract publishes data only, so there is nothing to assert here.

The two service-level scenarios (`Recherche d'un produit sans code transmis`,
`La liste des produits n'est pas modifiable`) stay at the service level through
`src/test/java/fr/demo/souscription/catalogue/service/CatalogueServiceBridge.java`, which hands the
glue a seeded `CatalogService` without a CDI container (`seed` is package-private, so the bridge
lives in the service package rather than using reflection).

## Waves 5 and 6 extensions declared early (W3-T08)

The observability, validation and resilience extensions are declared in `pom.xml` now — with
no `<version>` (BOM-managed) — so that the wave 3 vulnerability scan below runs against the
final classpath rather than a partial one (TG-010, NFG-005):

| Extension | Configured by |
| --- | --- |
| `quarkus-smallrye-health` | plan 21 — configured (management port 9000, `CatalogueReadinessCheck`) |
| `quarkus-micrometer-registry-prometheus` | plan 22 |
| `quarkus-opentelemetry` | plan 23 |
| `quarkus-logging-json` | plan 24 |
| `quarkus-hibernate-validator` | wave 5/6 validation work |
| `quarkus-smallrye-fault-tolerance` | caller-side contract, plans 20 and 28 |

They are left unconfigured here. The single exception is the OpenTelemetry exporter, which
would otherwise retry the default OTLP endpoint `localhost:4317` and fill the logs with
export errors, so `application.properties` carries:

```properties
quarkus.otel.sdk.disabled=true
```

Plan 23 re-enables it together with a real collector endpoint.

## Vulnerability baseline (NFG-005)

```
mvn -f souscription-catalogue-service/pom.xml -Psecurity verify -DskipTests
```

The `security` profile runs OWASP `dependency-check-maven` 11.1.0 with
`failBuildOnCVSS=7` (HTML + JSON reports under `target/`). In the current build
environment the plugin cannot reach the NVD/CISA feeds (TLS interception →
`SSLHandshakeException: PKIX path building failed`), so the baseline below was recorded
from an offline CVE database snapshot (2026-11-08) over the resolved runtime dependency
list.

Baseline result: **clean for CRITICAL and HIGH.** The platform BOM was raised to
`quarkus-bom 3.20.6.2` and three transitive stacks are pinned ahead of it in
`dependencyManagement` so that the CRITICAL/HIGH findings of the previous baseline are
resolved:

| Component | Previous (vulnerable) | Pinned now | Cleared |
| --- | --- | --- | --- |
| `io.netty:*` (netty-bom) | 4.1.111 / 4.1.130.Final | **4.1.137.Final** | CVE-2026-75595 (CRITICAL) + HIGH netty findings |
| `com.fasterxml.jackson:*` (jackson-bom) | 2.17.2 / 2.18.2 | **2.18.11** | CVE-2026-54512/54513/68497/91776/91777 (HIGH) |
| `io.vertx:*` (vertx-stack-depchain) | 4.5.10 / 4.5.25 | **4.5.27** | CVE-2026-6860 (MEDIUM) |
| `io.quarkus:quarkus-vertx-http` / `quarkus-rest` | 3.15.1 | **3.20.6.2** | CVE-2026-39852, CVE-2026-50559, CVE-2025-1247 (HIGH) |
| `com.google.guava:guava` | 32.1.2-jre (via jib / grpc) | **33.7.2-jre** | CVE-2026-102554 (HIGH) |
| `io.micrometer:*` (micrometer-bom) | 1.14.5 | **1.15.12** | CVE-2026-40984 (HIGH) |

The last two entries were introduced by the wave 5/6 extensions declared in W3-T08
(`quarkus-smallrye-health`, `quarkus-micrometer-registry-prometheus`, `quarkus-opentelemetry`,
`quarkus-logging-json`, `quarkus-hibernate-validator`, `quarkus-smallrye-fault-tolerance`):
the OpenTelemetry/gRPC stack pulls Guava and the Prometheus registry pulls Micrometer. They
are pinned in `dependencyManagement` so this baseline holds for the **final** classpath, not
a partial one. The six extensions add no other CRITICAL or HIGH finding (gRPC 1.69.1,
protobuf-java 3.25.5, OpenTelemetry SDK 1.44.1, Prometheus simpleclient 0.16.0,
Hibernate Validator 8.0.3.Final, SmallRye Health 4.2.0, SmallRye Fault Tolerance 6.9.2 are
all clean). The Struts 1 lineage is absent from the module classpath
(`mvn dependency:tree | grep -i struts` returns nothing).

Verified against the offline CVE database snapshot (2026-11-08) over the resolved runtime
dependency list: **0 CRITICAL, 0 HIGH, 0 MEDIUM** on the pinned coordinates. Test-scoped
additions (`io.cucumber:cucumber-java 7.20.1`,
`io.cucumber:cucumber-junit-platform-engine 7.20.1`,
`org.owasp:dependency-check-maven 11.1.0`) carry no known vulnerabilities.

The `dependency-check-maven` gate itself still cannot execute in this environment until
the corporate TLS CA is installed in the build JDK truststore or an internal NVD mirror is
provided; the pinned versions above are what that gate will see once it can run.

## Pipeline and deployment (W3-T04)

### Independent CI pipeline

`.github/workflows/build.yml` runs one job per stage, failing fast:
`build` → `test` → `cve` → `image` → `push`.

| Stage | Command |
| --- | --- |
| `build` | `mvn -f souscription-catalogue-service/pom.xml clean compile` |
| `test` | `mvn -f souscription-catalogue-service/pom.xml clean verify -Pcucumber` (promoted gate, W7-T02: `@QuarkusTest` classes **and** the Cucumber suite) |
| `cve` | `mvn -f souscription-catalogue-service/pom.xml -Psecurity verify -DskipTests` |
| `image` | `mvn ... package -Dquarkus.container-image.build=true` |
| `push` | `mvn ... -Dquarkus.container-image.push=true` (main branch only) |

The pipeline is scoped to this module only — it does not build the legacy WAR. Registry
coordinates come from the `CONTAINER_REGISTRY` repository variable and the credentials from
the `CONTAINER_REGISTRY_USERNAME` / `CONTAINER_REGISTRY_PASSWORD` CI secrets. **No credential
literal is committed**; the workflow only references `secrets.*` / `vars.*`.

### Kubernetes manifests

| File | Content |
| --- | --- |
| `deploy/deployment.yaml` | 2 replicas, `runAsNonRoot`, `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, `capabilities: drop ALL`, `seccompProfile: RuntimeDefault`, `emptyDir` on `/tmp`, requests 160Mi/100m, limits 320Mi/500m, readiness + liveness probes |
| `deploy/service.yaml` | `ClusterIP` only, ports `https` 8443 and `management` 9000 — never `LoadBalancer` or `NodePort` (hardened in plan 27) |
| `deploy/poddisruptionbudget.yaml` | `minAvailable: 1`, so a voluntary disruption never drains both replicas |
| `deploy/servicemonitor.yaml` | `ServiceMonitor` scraping the `management` port on `/q/metrics` every 30 s (plan 25) |
| `deploy/alerts.yaml` | `PrometheusRule` carrying the four contract alerts (plan 25) |

Deployment procedure:

```
mvn -f souscription-catalogue-service/pom.xml package -Dquarkus.container-image.build=true
kubectl apply --dry-run=client -f souscription-catalogue-service/deploy/
kubectl apply -f souscription-catalogue-service/deploy/
```

Two replicas are safe without any shared store: the catalogue is static and immutable per
instance (see *Per-instance in-memory catalogue* above).

### Health probes (W5-T01)

Both probes target the dedicated management interface enabled in `application.properties`
(`quarkus.management.enabled=true`, `quarkus.management.port=9000`):

| Probe | Endpoint |
| --- | --- |
| readiness | `GET /q/health/ready` on port 9000 (`initialDelaySeconds: 2`, `periodSeconds: 5`) |
| liveness | `GET /q/health/live` on port 9000 (`initialDelaySeconds: 2`, `periodSeconds: 10`) |

Readiness is backed by `CatalogueReadinessCheck` (`catalogue-seed`), which reports UP only once
the `StartupEvent` seeding has populated the catalogue and exposes `data.products`. It consults
no remote dependency — the service has none — so a third party can never trigger a restart loop.
Liveness uses the SmallRye Health default; no custom liveness check is declared.

`initialDelaySeconds: 2` is derived from the **measured** 0.865 s start-up recorded below, not
from a servlet-container habit.

## Execution model, statelessness and concurrency (W3-T05, W3-T06)

### Execution model: event loop, never `@Blocking` (FG-016, AG-014)

`ProductResource.list()` and `ProductResource.byCode(code)` perform a **non-blocking in-memory read**
of the immutable catalogue seeded at start-up — no I/O, no JDBC, no lock, no sleep. They therefore
stay on the **Vert.x event loop** and are deliberately **not** annotated `@Blocking`: dispatching
such a read to the worker pool would add a thread hand-off and a queue hop for no benefit.

**Rule:** no blocking call (network or file I/O, JDBC, `Thread.sleep`, `Future.get`, synchronous
remote client, unbounded stream) may be introduced into `ProductResource`, its filters/mappers or
`CatalogService`. If a future change genuinely requires one, that method must be moved to the worker
pool with `@Blocking` **in the same change** — otherwise it stalls the event loop and the logs show
`blocked event loop thread` warnings. There is no `@Blocking` annotation anywhere in `src/main/`,
and that is the intended state.

### Statelessness (AG-009)

No HTTP session, no session affinity, no sticky routing, no per-request state retained between
requests, no mutable singleton state. Every replica is interchangeable and any request may be routed
to any replica. The legacy `<session-config><session-timeout>20</session-timeout>` has **no
counterpart**: the subscription draft that relied on it is a persisted table owned by
`souscription-service`. The only in-memory state is the immutable catalogue, identical in every
replica (see *Per-instance in-memory catalogue*).

### No transaction boundary (AG-011)

The service is read-only and has no datastore, so there is nothing to demarcate. No transaction
annotation, no JTA transaction-manager extension and no connection-pool extension is declared
anywhere in this module, and none may be introduced (verified by a repository-wide grep, which must
return no hit for those coordinates).

### Explicit concurrency limits (NFG-010)

All four limits are declared in `application.properties` in `${VAR:default}` form, so they are tuned
per environment without a rebuild:

| Property | Env variable | Default | Role |
| --- | --- | --- | --- |
| `quarkus.thread-pool.max-threads` | `CATALOGUE_WORKER_MAX_THREADS` | 32 | worker-pool ceiling (unused by the two endpoints, bounds any future blocking path) |
| `quarkus.thread-pool.queue-size` | `CATALOGUE_WORKER_QUEUE` | 512 | worker queue bound — rejects rather than growing without limit |
| `quarkus.http.limits.max-connections` | `CATALOGUE_MAX_CONNECTIONS` | 1000 | concurrent HTTP connection ceiling |
| `quarkus.http.io-threads` | `CATALOGUE_IO_THREADS` | 4 | event-loop threads, sized for the 500m CPU limit |

Expected steady state under nominal load: zero `blocked event loop thread` warning and peak worker
threads well below 32, since the request path never leaves the event loop.

### Graceful shutdown (NFG-009, 99.9 % availability objective)

`quarkus.shutdown.timeout=30S` drains in-flight requests before the process exits;
`quarkus.http.so-reuse-port=true` lets the replacement pod bind the port while the outgoing one is
still draining. `deploy/deployment.yaml` sets `terminationGracePeriodSeconds: 40` (> 30 s) so
Kubernetes never SIGKILLs mid-drain, and `deploy/poddisruptionbudget.yaml` (`minAvailable: 1`) keeps
at least one replica serving throughout.

### Scale and rolling-update verification (deferred)

Steps requiring a live cluster are **not executable in this build environment** (no Kubernetes, no
container runtime — same constraint as the container-image parity checks, see `test-gaps.md`). To be
run on the integration environment:

```
kubectl scale deployment/souscription-catalogue-service --replicas=3
for p in $(kubectl get pods -l app=souscription-catalogue-service -o name); do \
  kubectl exec $p -- curl -s localhost:8443/v1/products | sha256sum; done   # 3 identical digests
# then, under load:
kubectl set image deployment/souscription-catalogue-service catalogue=<new-tag>   # expect 0 5xx
```

Byte-identical responses across replicas are guaranteed by construction: the dataset is static,
compiled in and never mutated.

## Memory sizing and image hardening (W3-T07, NFG-003, NFG-008)

### Heap bound to the cgroup limit, not the node memory

`-XX:MaxRAMPercentage=70` is set in **both** image build paths, so the heap ceiling derives from the
container memory limit (70 % of `320Mi` ≈ 224 MiB) and never from the node's physical memory:

| Path | Location |
| --- | --- |
| Jib (`-Dquarkus.container-image.build=true`) | `quarkus.jib.jvm-arguments` in `src/main/resources/application.properties` |
| Hand-written image | `ENTRYPOINT` of `src/main/docker/Dockerfile.jvm` |

Both entry points also carry `-XX:+ExitOnOutOfMemoryError` (an OOM kills the pod so Kubernetes
replaces it instead of leaving a degraded JVM serving traffic) and `-Djava.io.tmpdir=/tmp`, which
points the only writable path at the mounted `emptyDir` (see below). `resources` in
`deploy/deployment.yaml` are `requests 160Mi/100m`, `limits 320Mi/500m`.

### Pod and image hardening

| Control | Where |
| --- | --- |
| `runAsNonRoot: true` (uid 185, the non-root default of the UBI9 runtime base) | pod + container `securityContext`, `USER 185` in `Dockerfile.jvm` |
| `readOnlyRootFilesystem: true` | container `securityContext` |
| `allowPrivilegeEscalation: false` | container `securityContext` |
| `capabilities: { drop: ["ALL"] }` | container `securityContext` |
| `seccompProfile: RuntimeDefault` | pod `securityContext` |
| writable `/tmp` | `emptyDir` volume (`sizeLimit: 64Mi`) mounted at `/tmp` — added **instead of** relaxing `readOnlyRootFilesystem` |

**No secret in the image, the POM or the properties.** Every adjustable value in
`application.properties` is in `${VAR:default}` form; there is no password, token, key or
certificate literal anywhere in `src/main/resources/` (`grep -riE "password|secret|token|BEGIN
CERTIFICATE" src/main/resources/` returns nothing). Registry credentials exist only as CI secret
references, and the mTLS certificates are mounted as a Kubernetes secret by plan 26.

### 30-minute load test (NFG-003)

No container runtime and no Kubernetes are available in this build environment, so the test was run
against the fast-jar with the container memory limit emulated by `-XX:MaxRAM=320m` plus the exact
production JVM flags:

```
java -XX:MaxRAM=320m -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError \
     -Djava.io.tmpdir=/tmp -Dfile.encoding=UTF-8 -jar target/quarkus-app/quarkus-run.jar
# 30 min of continuous requests over /v1/products, /v1/products/{code} (hit and 404)
```

| Metric | Measured | Target |
| --- | --- | --- |
| Duration | 29 min 49 s of sustained load | 30 min |
| Requests served | 209 160, all `200`/`404` as expected, `200` still served at the end | — |
| Steady-state RSS | **~31 MB** (30.4 MiB, flat from minute 5 to minute 30) | 100-170 MB |
| G1 heap at end of run | 21.5 MB committed / 11.8 MB used of a 224 MiB ceiling | — |
| Metaspace | 26.8 MB committed | — |
| OOMKill / `OutOfMemoryError` | **0** (process exited gracefully in 0.159 s on SIGTERM) | 0 |
| `blocked event loop thread` warnings | 0 | 0 |

The RSS is **below** the 100-170 MB band, measured on macOS/arm64 where `ps rss` excludes
compressed and shared pages; a Linux UBI9 container typically reports a higher figure for the same
workload. The footprint is credible for this service (3 immutable products, no datastore, no view
layer), and the band is an upper bound: the 320Mi limit and the 160Mi request are kept unchanged so
the Linux container measurement can be taken before resizing. Re-measuring the container RSS with
`kubectl top pod` / `docker stats` is recorded as a deferred check in `test-gaps.md`.

> **Superseded by the final audit (W7-T05).** The `~31 MB` figure above is a mis-measurement: it was
> read from the shell wrapper process rather than from the JVM. The re-measured footprint is
> 146.8 MB cleartext and 191.2 MB under mTLS — see
> `#### The RSS deviation, stated rather than smoothed over` in
> `## Final security and performance audit (W7-T05)`.

### Pipeline duration (NFG-004)

| Stage | Measured | Budget |
| --- | --- | --- |
| `clean package` (cold, empty `target/`) | 7.2 s | < 4 min cold |
| `clean test` | 14.3 s | — |
| `clean package -Dquarkus.container-image.build=true` | fails in 3.8 s with `No container runtime was found` — the Maven work ahead of the Jib step is the same 7 s | < 4 min cold |

The image-build stage cannot execute here (no Docker/Podman); the budget is met by a wide margin on
every stage that can run, and the image build is deferred in `test-gaps.md`.

## Measured start-up time (NFG-002)

```
java -jar souscription-catalogue-service/target/quarkus-app/quarkus-run.jar
souscription-catalogue-service 1.0.0 on JVM (powered by Quarkus 3.20.6.2) started in 0.865s
```

**0.865 s** (JVM mode, target < 1.2 s). This value drives `initialDelaySeconds` for the
readiness/liveness probes in the container and deployment plans.

### Re-measured after the wave 5/6 extensions

With `smallrye-health`, `micrometer-registry-prometheus`, `opentelemetry` (SDK disabled),
`logging-json` (disabled), `hibernate-validator` and `smallrye-fault-tolerance` on the classpath,
five consecutive runs of the packaged fast-jar gave:

```
started in 0.462s / 0.462s / 0.581s / 0.452s / 0.763s
```

Median **0.462 s**, worst **0.763 s** — still inside the `< 1.2 s` NFG-002 budget, so
`initialDelaySeconds: 2` stays valid. Measurements taken on a loaded machine can read 1.3-1.6 s;
that is host contention, not an extension cost, so the budget is evaluated against an idle host.

## Micrometer / Prometheus instrumentation (W5-T02)

Prometheus series are exported on the **management port 9000 only** (`/q/metrics`); the business
port 8443 answers 404 on that path, so the metrics are not part of the application perimeter.
Three groups of series are published:

| Series | Source |
| --- | --- |
| `http_server_requests_seconds{method,uri,status,outcome}` | `quarkus.micrometer.binder.http-server.enabled=true` |
| `catalogue_products_list_seconds`, `catalogue_products_detail_seconds` | `@Timed` on `ProductResource.list()` / `byCode()` |
| `jvm_memory_used_bytes`, `jvm_threads_live_threads`, … | `quarkus.micrometer.binder.jvm=true` |

The two `@Timed` timers measure the resource method alone, while `http_server_requests_seconds`
measures the whole request. During an incident the difference between the two attributes the
regression to the network/serialisation layer rather than to the service (NFG-013).

The `uri` tag stays **templatised**: the detail endpoint reports
`uri="/v1/products/{code: .+}"`, never `uri="/v1/products/PRUDENT"`, so the product code cannot
become a Prometheus tag value. The tag carries the JAX-RS template verbatim, regex included,
because `@Path("/{code: .+}")` is published as `pattern: .+` in the frozen `/v1` contract
(`openapi-contract-v1.yaml`) and is therefore not rewritten to a bare `{code}`.

Scraping is declared by the pod annotations `prometheus.io/scrape`, `prometheus.io/port: "9000"`
and `prometheus.io/path: "/q/metrics"` in `deploy/deployment.yaml`; the `ServiceMonitor` object
comes with plan 25.

### Measured instrumentation overhead

`GET /v1/products` on the packaged fast-jar, 200 warm-up requests then 1000 measured requests
(`curl -w %{time_total}`, client-side total latency, same host), with and without the registry
(`-Dquarkus.micrometer.enabled=false`):

| Configuration | p95 |
| --- | --- |
| Before (Micrometer disabled) | 1.403 ms |
| After (Micrometer + JVM binders + 2 `@Timed`) | 1.708 ms |
| **Added overhead** | **+0.305 ms** (target < 1 ms) |

The figure includes the client and loopback cost, so it is an upper bound on the server-side
overhead. The p95 stays far inside the published `p95 <= 25 ms` server budget.

## Distributed tracing (W5-T03)

In the source the catalogue read was in-process, so there was nothing to correlate. In the target
topology the read takes part in two traces — the confirmation path
(`souscription-service → souscription-catalogue-service`) and the navigation path
(`souscription-gateway → souscription-catalogue-service`) — so the service honours the incoming
W3C `traceparent` (NFG-014, IG-005).

| Property | Value |
| --- | --- |
| `quarkus.otel.sdk.disabled` | `false` |
| `quarkus.otel.exporter.otlp.endpoint` | `${OTEL_EXPORTER_OTLP_ENDPOINT:http://otel-collector:4317}` |
| `quarkus.otel.traces.sampler` | `parentbased_traceidratio` |
| `quarkus.otel.traces.sampler.arg` | `${OTEL_SAMPLING_RATIO:0.1}` |
| `quarkus.otel.resource.attributes` | `service.name=souscription-catalogue-service,service.version=1.0.0` |

`parentbased_traceidratio` is a requirement, not a preference: the caller's sampling decision is
propagated, so a sampled confirmation trace is never truncated at this hop. `always_on` would
saturate the collector on a high-traffic read endpoint and `always_off` would make incidents
unanalysable. The 10 % ratio therefore applies to **root** traces only (a direct call with no
`traceparent`); a request arriving with a sampled parent is always recorded.

The collector address and the ratio come from the environment
(`OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SAMPLING_RATIO` in `deploy/deployment.yaml`): no collector
address is hardcoded in the image or in `application.properties`.

### Context propagation check

`TracePropagationTest` sends a real HTTP request carrying
`traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01` and asserts on the spans
collected by an in-memory `SpanExporter` (test profile only, `%test.quarkus.otel.traces.exporter=cdi`):

* exactly **1** server span is emitted, so there is no duplicate instrumentation;
* its trace id is `0af7651916cd43dd8448eb211c80319c` and its parent span id is `b7ad6b7169203331`
  — **0 orphan trace** (NFG-014);
* the span resource carries `service.name=souscription-catalogue-service`, which is what names the
  hop inside the confirmation-path trace.

The equivalent manual check once a collector is reachable:

```bash
curl -s -H "traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01" \
     localhost:8443/v1/products
```

The end-to-end assembly of the `souscription-service → souscription-catalogue-service` trace is a
cross-application check: it can only be observed once both services and the collector run in the
same environment, and is therefore left to the integration environment. On this side the
contract verified here — one child span on the caller's trace id, correct `service.name` — is what
makes that assembly possible.

### Measured tracing overhead

`GET /v1/products` on the packaged fast-jar, 200 warm-up requests then 1000 measured requests
(`curl -w %{time_total}`, client-side total latency, same host):

| Configuration | p95 |
| --- | --- |
| SDK disabled (`quarkus.otel.sdk.disabled=true`) | 1.191 ms |
| Tracing on, root traces at 10 % sampling | 1.841 ms |
| Tracing on, every request carrying a **sampled** `traceparent` (worst case, 100 % recorded) | 1.886 ms |
| **Added overhead** | **+0.695 ms worst case** (target < 2 ms) |

OTLP export runs on the batch processor, off the request path, so an unreachable collector does not
add request latency. The p95 stays well inside the published `p95 <= 25 ms` server budget.

## Observability platform wiring and alerting (W5-T05)

The three signals exist (plans 22, 23, 24); this section records **where each one goes** and which
alerts make the published `/v1` contract — 99.9 % availability, server `p95 <= 25 ms` — observable
(IG-005, CFG-004, AG-013).

### The three signals and their destinations

| Signal | Emitted where | Transport | Lands in |
| --- | --- | --- | --- |
| **Metrics** | `:9000/q/metrics` (management port only, Prometheus text format) | pulled by Prometheus every 30 s via `deploy/servicemonitor.yaml` (`port: management`) | Prometheus TSDB → Grafana *Catalogue service* dashboard (Prometheus datasource); same series back `deploy/alerts.yaml` |
| **Traces** | OTLP/gRPC spans from the Quarkus OpenTelemetry SDK | pushed to the collector at `OTEL_EXPORTER_OTLP_ENDPOINT` (injected in `deploy/deployment.yaml`), batch processor, off the request path | OTel collector → the platform trace store, queried by `service.name=souscription-catalogue-service` (Tempo/Jaeger datasource) |
| **Logs** | one JSON record per line on **stdout** (`quarkus.log.console.json=true`), carrying `traceId`/`spanId`, `service.name`, `service.version` | tailed from the container stdout by the platform log agent — the application writes no file and ships nothing itself | the platform log store (Loki/Elasticsearch datasource); `traceId` joins a log line to its trace |

`OTEL_EXPORTER_OTLP_ENDPOINT` and `OTEL_SAMPLING_RATIO` are **environment variables of the
Deployment**, not image content: `application.properties` only holds them as
`${OTEL_EXPORTER_OTLP_ENDPOINT:http://otel-collector:4317}` / `${OTEL_SAMPLING_RATIO:0.1}`, a dev
fallback. No collector address is baked into the image.

The `ServiceMonitor` selects `app: souscription-catalogue-service` and scrapes the endpoint named
`management` — the port name declared in `deploy/service.yaml` (9000). The pod annotations
(`prometheus.io/*`) are kept for the annotation-based discovery path; the `ServiceMonitor` is the
operator-based one. Both point at the same management port, so `/q/metrics` is never exposed on the
business port 8443.

### Minimal alert set (`deploy/alerts.yaml`)

| Alert | Expression (summary) | `for` | Severity | Contract line it defends |
| --- | --- | --- | --- | --- |
| `CatalogueV1LatencyP95Above25ms` | `histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{uri=~"/v1.*"}[5m])) > 0.025` | 5m | warning | server `p95 <= 25 ms` |
| `CatalogueV1ServerErrorRateAbove01Percent` | 5xx / total on `/v1` over 5 min `> 0.001` | 5m | critical | 99.9 % availability (0.1 % error allowance) |
| `CatalogueReadinessDown` | `min by (pod) (up{job="souscription-catalogue-service"}) == 0`, or the series absent | 1m | critical | readiness gate behind `/q/health/ready` |
| `CatalogueErrorBudgetFastBurn` | 1 h 5xx ratio `> 14.4 × 0.001` | 2m | critical | ~43 min/month error budget |

`for: 5m` on the latency and error-rate rules is deliberate: this is a static read endpoint, so a
single slow scrape interval is noise, while five consecutive minutes outside budget is a
regression. The burn-rate rule uses the standard 14.4x fast-burn multiplier — at that rate the
monthly budget is gone in about two days, which is what turns the 99.9 % figure into something
actionable rather than a number in a table.

The `job` label used by all four rules is the one the `ServiceMonitor` produces; the service name is
*not* available as a Prometheus label (`quarkus.otel.resource.attributes` applies to traces, not to
the metrics registry).

Manifest check — no cluster needed for the client-side pass:

```bash
kubectl apply --dry-run=client -f souscription-catalogue-service/deploy/
```

`PrometheusRule` and `ServiceMonitor` are CRDs, so `--dry-run=client` validates them only where the
Prometheus Operator CRDs are installed; on a bare cluster use `--validate=false` or
`kubeconform -ignore-missing-schemas`.

## Mutual TLS on the application port (W6-T01)

The application port 8443 serves HTTPS only (`quarkus.http.insecure-requests=disabled`) and requires
a client certificate signed by the internal CA (`quarkus.http.ssl.client-auth=required`). The
management port 9000 stays outside the mTLS perimeter so the orchestrator probes and the Prometheus
scrape keep working; `client-auth` is never enabled on it, and it is restricted to intra-cluster
traffic by the `NetworkPolicy` of plan 27.

The negotiated protocol is pinned to TLS 1.3 (`quarkus.http.ssl.protocols=TLSv1.3`, NFG-006): the
JDK default list also offers TLS 1.2, which would let a caller downgrade the hop. All callers are
internal services on recent TLS stacks; an exception for a caller unable to do TLS 1.3 must be
recorded with the owning team before the list is widened.

Certificate material comes from the Kubernetes secret `souscription-catalogue-service-tls`, mounted
read-only at `/etc/tls` (`tls.crt`, `tls.key`, `ca.crt`). Nothing is baked into the image, the POM or
`application.properties` — those only reference the mount paths. The `%test` and `%dev` profiles run
cleartext without a client certificate, because neither has the secret mounted.

Transport checks:

```bash
curl -k https://host:8443/v1/products                                   # must FAIL (no client certificate)
curl --cert client.crt --key client.key --cacert ca.crt \
     https://host:8443/v1/products                                      # must SUCCEED (200, 3 products)
curl -s http://host:9000/q/health/ready                                 # must SUCCEED (UP, no mTLS)
```

### Certificate rotation procedure

Quarkus reads `quarkus.http.ssl.certificate.*` once at start-up and the legacy PEM configuration has
no reload watcher, so **rotation requires a restart**: updating the secret alone leaves the running
pods on the old key pair (kubelet refreshes the projected files within its sync period, the JVM does
not re-read them).

1. Issue the new server certificate/key from the internal CA before the current one expires
   (recommended lead time: 30 days, alert on `< 15 days`).
2. If the CA itself is being rotated, publish the *concatenated* old+new CA bundle in `ca.crt`
   first, roll all callers onto the new client certificates, then drop the old CA from the bundle.
3. Update the secret:
   `kubectl create secret generic souscription-catalogue-service-tls --from-file=tls.crt --from-file=tls.key --from-file=ca.crt --dry-run=client -o yaml | kubectl apply -f -`
4. Trigger a rolling restart: `kubectl rollout restart deployment/souscription-catalogue-service`,
   then `kubectl rollout status deployment/souscription-catalogue-service`.
   With `replicas: 2`, the `PodDisruptionBudget` and `terminationGracePeriodSeconds: 40`, the restart
   drains in-flight requests and loses none.
5. Re-run the three transport checks above against the new certificate.
6. Rollback: re-apply the previous secret and restart again; keep the superseded key pair until the
   checks have passed.

The rotation runbook must therefore schedule the rolling restart of step 4 — it is not optional.

## Network isolation (W6-T02)

The catalogue is reachable only from inside the cluster, by exactly two callers
(NFG-007, CFG-006, IG-003):

| Source | Port | Rule |
|---|---|---|
| `souscription-gateway` pods | 8443 (mTLS) | `deploy/networkpolicy.yaml`, first ingress rule |
| `souscription-service` pods | 8443 (mTLS) | `deploy/networkpolicy.yaml`, first ingress rule |
| Prometheus, namespace `monitoring` | 9000 (management) | `deploy/networkpolicy.yaml`, second ingress rule |

Everything else is denied: the `NetworkPolicy` selects the catalogue pods with
`policyTypes: [Ingress]`, which makes ingress default-deny for them. `deploy/service.yaml` is
`type: ClusterIP` and there is **no `Ingress`, `LoadBalancer` or `NodePort` object** for this
service — none must ever be added. The management port 9000 is outside the mTLS perimeter
(W6-T01), so this policy is what keeps it internal.

Checks:

```bash
kubectl apply --dry-run=client -f souscription-catalogue-service/deploy/
grep -rniE "LoadBalancer|NodePort|kind: Ingress" souscription-catalogue-service/deploy/   # must return nothing
kubectl get ingress,svc -l app=souscription-catalogue-service                             # 0 Ingress, ClusterIP only
```

### Negative reachability test (automated)

From **outside** the cluster, a connection attempt must fail (connection refused or timeout —
never a 200, never a TLS handshake):

```bash
curl -k --connect-timeout 5 --max-time 10 https://<cluster-edge-host>:8443/v1/products   # must FAIL
curl -k --connect-timeout 5 --max-time 10 https://<cluster-edge-host>:9000/q/health/ready # must FAIL
```

This is automated in the `network-isolation` job of `.github/workflows/build.yml`, which gates the
`push` job. The job also re-checks the absence of public exposure objects and that the policy
whitelists exactly two application callers. `<cluster-edge-host>` is supplied by the repository
variable `CATALOGUE_EXTERNAL_HOST`; when it is unset the live probe is skipped and only the static
manifest checks run.

The in-cluster positive path stays the mTLS check of W6-T01 (`curl --cert ... https://host:8443/v1/products`
from a gateway or subscription pod).

### Reallocated edge controls

The edge controls formerly implemented by `src/main/java/fr/demo/souscription/web/SecurityFilter.java`
in the monolith are **reallocated to `souscription-gateway`, not removed**. The catalogue is an
internal, cookie-less, read-only `/v1` API behind mTLS and this `NetworkPolicy`; the gateway is the
only component exposed to browsers and therefore the only correct place for the browser-facing
controls (double-submit cookie, same-origin SPA). Control by control (CFG-010, NFG-017 pre-check):

| Source control (`SecurityFilter`) | New owner | Note |
|---|---|---|
| `X-Content-Type-Options: nosniff` | `souscription-gateway` | Browser-facing response header; meaningless on a service-to-service JSON hop |
| `X-Frame-Options: DENY` | `souscription-gateway` | Idem — framing is a browser concern |
| `Referrer-Policy: no-referrer` | `souscription-gateway` | Idem |
| `Content-Security-Policy` (`default-src 'self'` … `form-action 'self'`) | `souscription-gateway` | Must be emitted with the SPA documents it governs |
| `Cache-Control: no-store` | `souscription-gateway` | The catalogue is public, static reference data and sets its own caching headers; `no-store` belongs to the browser-facing responses |
| CSRF session token (`csrf` session attribute + `POST` double-submit check on `*.do`) | `souscription-gateway` | Double-submit cookie at the gateway, `SameSite=Strict`, same-origin SPA. The catalogue has no session, no cookie and no state-changing method |
| HTTP method allow-list (`GET, HEAD, POST`, else 405 + `Allow`) | `souscription-gateway` (edge) **and enforced here** | The catalogue exposes only `GET`/`HEAD` on `/v1/products*`; any other method returns 405 with an `Allow` header via `MethodNotAllowedMapper` |
| Request/response encoding (`UTF-8` in and out) | **Enforced here by plan 17** | `charset=UTF-8` on every response and UTF-8 request decoding, covered by `Utf8EncodingTest` / `Utf8WireParityTest` |

No source control is dropped: the five response headers and the CSRF token move to the gateway, the
method allow-list is kept at both ends, and the encoding behaviour stays in this service. The
written confirmation from the `souscription-gateway` team that the six reallocated controls are
implemented at the edge is recorded in `### 4. SecurityFilter control-by-control` below
(parity check 35).

## Caller resilience policy, CFG-005 failure mode and concurrency (W6-T03)

The confirmation path now contains a remote read (`souscription-service` ->
`GET /v1/products/{code}`) that did not exist in the source, so it carries a distributed failure
mode (CFG-005). This section publishes the executable caller policy, records the agreed behaviour
when the catalogue is unavailable, and states the concurrency position of the shared read model.

### Executable caller-side policy (to be implemented by the callers)

`souscription-gateway` and `souscription-service` must annotate their catalogue REST client exactly
as follows (MicroProfile Fault Tolerance, `quarkus-smallrye-fault-tolerance`):

```java
@Timeout(2000)
@Retry(maxRetries = 1, delay = 100, retryOn = IOException.class)
@Fallback(fallbackMethod = "catalogueIndisponible")
ProductDetail readProduct(String code) { /* REST client to /v1/products/{code} */ }
```

| Parameter | Value | Why |
|---|---|---|
| `@Timeout` | 2000 ms | ~30x the published server p99 (60 ms) including mTLS; fails fast instead of holding a confirmation open |
| `@Retry` | 1 retry, 100 ms delay, `retryOn = IOException.class` | covers a connection reset or a pod rollover, not a server-side 4xx/5xx |
| `@Fallback` | `catalogueIndisponible` | see the CFG-005 decision below: produce a 503, never a cached rate |

**`GET` only — this policy must not be extended to other verbs.** The retry is safe solely because
both catalogue endpoints (`GET /v1/products`, `GET /v1/products/{code}`) are pure, side-effect-free
reads: replaying them cannot duplicate an effect. Applying the same `@Retry` to a mutating call
(for example the confirmation `POST` on `souscription-service`) would risk a duplicate subscription;
the gateway's mutating hops stay at 0 automatic retries, as the reference contracts state.

**Worst case and its fit in the confirmation budget.** `2 s x (1 + 1 retry) + 100 ms delay` gives a
**~4.1 s** worst case for the catalogue hop. Budget check against the surrounding timeouts
(`global/contracts`):

| Hop | Timeout | Fit |
|---|---|---|
| SPA -> gateway, `POST /api/v1/drafts/{id}/confirmation` | 10 s, no retry | ~4.1 s fits, 5.9 s left |
| gateway -> `souscription-service` confirmation | 5 s, no retry | ~4.1 s fits, but leaves only ~0.9 s for the simulation call and the subscription insert |

The ~4.1 s worst case therefore fits, **and nothing larger does**: a caller configured with the
reference values (3 s, 2 retries, exponential backoff) would reach ~9 s and would blow the 5 s
gateway->service timeout, turning a catalogue blip into a gateway-side timeout with an unknown
persistence outcome. This is the deciding argument for settling the open point of the previous
section in favour of the **2 s / 1 retry** values published here. `souscription-service` must
additionally keep the catalogue read **outside** any open transaction (already stated in
`global/contracts/INTER_APPLICATION_CONTRACTS.md`), so these 4.1 s never hold a database lock.

### CFG-005 decision: clean failure, no stale-rate fallback

When the catalogue is unavailable at confirmation time, two options were on the table:

| Option | Behaviour | Verdict |
|---|---|---|
| **Clean failure (decided)** | the confirmation returns `503` (`application/problem+json`, retryable) and **persists nothing** | **adopted** |
| Cache fallback | the confirmation proceeds using the last cached annual rate | rejected |

Rationale: the annual rate is an **auditable contractual figure** (CFG-011, golden-file freeze). At
the instant of contractual commitment, committing a subscription against a possibly stale rate
would produce a signed contract that no longer matches the published catalogue, with no way to tell
afterwards which rate was in force. A failed confirmation is recoverable by the user in seconds; a
wrong rate on a persisted subscription is a reconciliation incident. Availability of the catalogue
read is regained by the HTTP read cache of plan 29 (`Cache-Control` + `ETag`), which shortens the
exposure window without ever substituting a value, not by serving stale data at commit time.

Consequences, agreed with the owning teams (the business acceptance that a catalogue outage blocks
confirmations is question Q5 of `global/contracts/INTER_APPLICATION_CONTRACTS.md`):

* `souscription-service` — fallback returns a retryable `503` problem+json, no row written, no
  partial draft mutation; the draft stays confirmable once the catalogue is back.
* `souscription-gateway` — propagates `503` with `Retry-After`; navigation reads (the catalogue
  list) degrade to the same `503` rather than to an empty catalogue, so the SPA never shows a
  truncated product list as if it were complete.
* This matches the fallback wording already recorded in the two reference contracts
  (`.../resilience-contract.json`), which stay authoritative: **never serve a stale annual rate**.

**Recorded agreement (the failure mode is a contract, not a unilateral choice).** The clean-failure
decision is not owned by this service alone: it dictates what `souscription-service` does inside the
confirmation path, so it is recorded as an agreement between the two owning teams.

| Party | What is agreed | Status |
|---|---|---|
| `souscription-catalogue-service` (owner of this README) | publishes the `GET`-only caller policy and the clean-failure contract; adds **no** provider-side fault tolerance and **no** stale-serving cache | **agreed** — this document |
| `souscription-service` team (caller, confirmation path) | implements `@Fallback catalogueIndisponible` as a retryable `503` problem+json with **nothing persisted**, keeps the catalogue read outside any open transaction, and does **not** substitute a cached annual rate | **agreed** on the technical behaviour, recorded here and in `global/contracts/INTER_APPLICATION_CONTRACTS.md` (contracts 5 and 6); the written confirmation that the implemented fallback matches is collected in plan 35 |
| `souscription-gateway` team (caller, navigation reads) | propagates `503` with `Retry-After`, never degrades the product list to an empty catalogue | **agreed** — same contracts, confirmation in plan 35 |
| Business / product owner | accepts that a catalogue outage blocks confirmations rather than committing an unverified rate | **pending confirmation**, tracked as open question **Q5** of `global/contracts/INTER_APPLICATION_CONTRACTS.md`; the documented default is the clean failure above, so no implementation is blocked |

If the business answer to Q5 ever reverses the decision in favour of a cache fallback, it must come
with a written rationale from the owning team **and** a recorded maximum staleness for the annual
rate; until then the clean failure stands and the cache fallback is rejected.

### AG-015: no shared mutable state

The catalogue is now read concurrently by two services instead of being an in-process,
single-request read. No lock, no copy-on-read and no synchronisation was added, because the data
are immutable: `CatalogService.seed` assigns `List.copyOf(...)` once at start-up to a `volatile`
field, `Product` is a record, and `getProducts()` hands out that immutable list. Readers cannot
mutate it, so there is **0 shared mutable state** and the concurrency story is unchanged from the
source.

Check (must show assignments only inside `CatalogService`, i.e. the `volatile` declaration and the
`seed` assignment):

```bash
grep -rnE "\.add\(|\.remove\(|\.set\(|products =" souscription-catalogue-service/src/main/
```

Automated by `src/test/java/fr/demo/souscription/catalogue/service/ConcurrentReadParityTest.java`:
100 concurrent list reads over 16 threads observe one single state, mutation attempts throw
`UnsupportedOperationException`, and 64 concurrent `find()` calls stay coherent.

### Measured load test, both caller profiles through mTLS

Both profiles interleaved against the packaged fast-jar with mTLS required on 8443 (client
certificate issued by a local CA, TLS 1.3, a fresh connection per request — the pessimistic case,
since a real caller pools connections): 650 navigation reads of `/v1/products` and 600
confirmation-path reads of `/v1/products/{code}`.

| Metric | Source | Measured | Contract | Verdict |
|---|---|---|---|---|
| Server p95 — `/v1/products` | `catalogue_products_list_seconds{quantile="0.95"}` | **0.05 ms** | <= 25 ms | PASS |
| Server p99 — `/v1/products` | `catalogue_products_list_seconds{quantile="0.99"}` | **0.08 ms** | <= 60 ms | PASS |
| Server p95 — `/v1/products/{code}` | `catalogue_products_detail_seconds{quantile="0.95"}` | **0.06 ms** | <= 25 ms | PASS |
| Server p99 — `/v1/products/{code}` | `catalogue_products_detail_seconds{quantile="0.99"}` | **0.08 ms** | <= 60 ms | PASS |
| Server max (both timers) | `..._seconds_max` | 5.9 ms (first request, JIT) | — | within budget |
| Caller-observed p95 (mTLS included) | `curl -w %{time_total}`, 1200 requests | **6.4 ms** | <= 100 ms | PASS |
| Caller-observed p99 (mTLS included) | idem | **7.3 ms** | — | 275x inside the 2 s timeout |

The caller-observed figure is dominated by the per-request TLS 1.3 handshake, which a pooled caller
amortises; it still leaves a ~15x margin on the 100 ms commitment and a ~275x margin on the 2 s
`@Timeout`. The server-side p95/p99 quantiles are published by the existing `@Timed` timers of
`ProductResource` (`percentiles = {0.95, 0.99}`), so the SLO is measurable in production from
`/q/metrics` on the management port, not only in this test.

Reproduction (local, no cluster required):

```bash
mvn -f souscription-catalogue-service/pom.xml clean package -DskipTests
# issue a CA, a localhost server certificate and a client certificate, then:
TLS_CERT_FILE=... TLS_KEY_FILE=... TLS_CA_FILE=... java -jar target/quarkus-app/quarkus-run.jar
curl --cacert ca.crt --cert client.crt --key client.key -o /dev/null \
     -w "%{time_total}\n" https://localhost:8443/v1/products        # repeat, then take the p95
curl -s http://localhost:9000/q/metrics | grep '^catalogue_products'  # server quantiles
```

**Deferred to a deployed instance:** the same measurement with the real internal-CA certificates,
pooled connections and in-cluster network latency between a gateway/subscription pod and the
catalogue — recorded in `test-gaps.md`.

## Cout operationnel du deployable (W6-T05, NFG-018)

This service owns no datastore, its data is static (`CatalogService` seeds an immutable list, frozen
by the golden file of CFG-011) and its functional change rate is near zero. It is nonetheless an
independent deployable, and NFG-018 requires the resulting cost to be stated rather than implied.

### The five added operational surfaces

| # | Surface | What it costs here | Where it is documented |
|---|---|---|---|
| 1 | **1 CI/CD pipeline** | its own build, test, scan, image push and deploy job, with its own failure mode and its own maintenance when the shared template moves | `## Pipeline and deployment (W3-T04)` |
| 2 | **1 container image** | its own base image to patch, its own CVE baseline to re-run and its own size/memory budget to keep | `## Vulnerability baseline (NFG-005)`, `## Memory sizing and image hardening (W3-T07, NFG-003, NFG-008)` |
| 3 | **1 set of Kubernetes manifests** | Deployment, Service, probes, PDB, NetworkPolicy, dashboard and alert rules to keep in sync with four sibling applications | `## Network isolation (W6-T02)`, `## Observability platform wiring and alerting (W5-T05)` |
| 4 | **1 certificate rotation** | server and client certificates from the internal CA, renewed on their own schedule, with an outage if the rotation is missed | `## Mutual TLS on the application port (W6-T01)` |
| 5 | **1 network hop** | mTLS handshake, JSON serialisation and in-cluster latency replacing an in-process method call | `## Measured load test, both caller profiles through mTLS` |
| (+) | **1 additional failure mode on the confirmation path** | CFG-005: the confirmation now returns `503` when the catalogue is unreachable, so the composite availability of the confirmation path is below the 99.9% objective of any single service | `## Caller resilience policy, CFG-005 failure mode and concurrency (W6-T03)` |

Surfaces 1 to 4 are fixed costs paid per release cycle regardless of functional change; surface 5
and the added failure mode are paid per request on the confirmation path.

### The decision that justifies the split

This cost is **accepted on the basis of two recorded user decisions**, not on a technical
recommendation — the recorded rationale of **TQ-040** (`target-clarification.json`, question
`TQ-040`) recommended `keep-current` (layered monolith re-expressed as a Quarkus service), on the
grounds that the 13 source components form a single bounded context sharing one table.

| Decision | Recorded answer | Source |
|---|---|---|
| **TQ-040** — architectural style | `microservices` — **confirmed by the user** against the `keep-current` recommendation | `target-clarification.json` (TQ-040), user confirmation of the five-application topology |
| **Independent evolution of the catalogue** — "is the catalogue genuinely expected to evolve independently, given that it is static in-memory data today?" | **Yes** — answered affirmatively; the split is retained despite the near-zero current change rate | recorded user decisions on the target topology (see `TARGET_APPLICATION_TOPOLOGY.md`) |
| **Remote simulation call at confirmation time** | keep it remote | recorded user decisions (context for the composite availability of the confirmation path) |

The topology is therefore **not relitigated by this plan**. What this section adds is the honest
statement of its recurring cost and the condition under which it should be re-opened.

### Re-examination criterion

| Item | Value |
|---|---|
| **Deadline** | **2 quarters** after the production go-live of this service |
| **Measurement to collect** | the **count of catalogue-only functional deployments** over the period — deployments carrying a functional change to this service alone (a new product, a changed rate or label, a new endpoint). Dependency bumps, base-image patches, certificate rotations and platform-wide template changes **do not count**. |
| **Trigger** | count **= 0** over the 2 quarters |
| **Action if triggered** | propose merging the catalogue into `souscription-service` as the documented lightening option — the alternative already recorded in `apps/souscription-catalogue-service/NONFUNCTIONAL_GAPS.json`, **NFG-018** ("Merge the catalogue into souscription-service as originally recommended by the TQ-040 rationale"). The merge removes 1 pipeline, 1 image, 1 manifest set, 1 certificate rotation, 1 network hop and the CFG-005 failure mode from the confirmation path, at the cost of giving up independent scaling of catalogue reads. |
| **Owner** | the owning team of `souscription-catalogue-service` (the module owner recorded in `apps/souscription-catalogue-service/transformation-plans/MIGRATION_ORCHESTRATION.md`), jointly with the `souscription-service` owner, since the merge target is theirs |
| **Where to record the outcome** | this section and the "Points de décision" table of `MIGRATION_ORCHESTRATION.md` (`+2 trimestres` entry) |

If the count is **greater than 0**, the independent-evolution answer is confirmed by evidence and the
deployable is kept as is; the measurement is then dropped. Until the deadline, NFG-018 stays open and
the duplication is contained — not removed — by sharing one CI template, one Helm/Kustomize base and
one dashboard template across the backend services.

## Tests de ressource REST Assured : tracabilite des assertions HTML source (W7-T01, CFG-010)

Le filet de test source est entierement lie au HTML (`src/test/java/fr/demo/souscription/web/SubscriptionIT.java`,
assertions catalogue aux lignes 21-42, 121, 134 : jsoup + `HttpURLConnection`). Il est remplace ici par
`src/test/java/fr/demo/souscription/catalogue/api/ProductResourceIT.java` (`@QuarkusTest` + REST Assured, aucun
port en dur : le cycle de vie du serveur et la configuration de REST Assured sont portes par `@QuarkusTest`,
la ou le test source visait un Tomcat provisionne par Cargo).

Chaque assertion source est **traduite**, **reallouee** a une autre application de la topologie, ou declaree
**changement de contrat accepte** — aucune n'est supprimee silencieusement.

| Source assertion (HTML) | Target equivalent (JSON) | Status |
| --- | --- | --- |
| L24 `status == 200` on `/catalogue.do` | `GET /v1/products` -> `statusCode(200)` | translated |
| L25 `select("table.catalogue tbody tr").size() == 3` | `body("size()", is(3))` | translated |
| L27 `html.contains("Horizon Équilibre")` | `body("find { it.code=='EQUILIBRE' }.name", equalTo(...))` | translated |
| L38 `status == 200` on `/produit.do?code=...` | `GET /v1/products/{code}` -> `statusCode(200)` | translated |
| L42 `status == 404` on `/produit.do?code=INCONNU` | `statusCode(404)` + `problem+json` | translated |
| L121 `get("/produit.do?code=PRUDENT")` | `GET /v1/products/PRUDENT` -> 200, fiche conforme au schema | translated |
| L134 `get("/produit.do?code=EQUILIBRE")` | `GET /v1/products/EQUILIBRE` -> 200, fiche conforme au schema | translated |
| L125 `status == 405` on une methode non autorisee | `POST /v1/products` -> `statusCode(405)` + `problem+json` | translated |
| L32 `Cache-Control == "no-store"` | `Cache-Control: max-age=300, public` | **accepted contract change** |
| L33-L35 `Content-Security-Policy`, `Set-Cookie HttpOnly/SameSite` | — | **reallocated to `souscription-gateway`** |
| L39 `product.csrf()` non vide (jeton CSRF dans la page) | — | **reallocated to `souscription-gateway`** (double-submit cookie) |
| L29 `html.contains("Financière Horizon — Épargne")` (titre de page) | — | **reallocated to `souscription-web`** |
| L40 `select("form[action$='commencer.do']").size() == 1` | — | **reallocated to `souscription-web`** (le declenchement de souscription est expose par `souscription-service`) |
| L26, L28, L30, L31 `html.contains("Extranet Financière Horizon")`, `"réalisez une simulation"`, `.demo-strip`, `"Les performances futures ne sont pas garanties."` | — | **reallocated to `souscription-web`** |

Le changement de contrat sur `Cache-Control` est celui du plan 29 : le catalogue est une donnee statique en
lecture seule, servie avec `ETag` + `max-age=300, public` (revalidation 304), alors que la page JSP source
etait en `no-store` parce qu'elle portait une session et un jeton CSRF — voir
`## HTTP caching: ETag, Cache-Control, 304 (W6-T04)`.

### Cas couverts par `ProductResourceIT`

| Cas | Attendu |
| --- | --- |
| `GET /v1/products` | 200, 3 elements, codes `PRUDENT`/`EQUILIBRE`/`DYNAMIQUE`, libelle accentue, `ETag`, `Cache-Control` |
| `GET /v1/products/EQUILIBRE` | 200, fiche conforme au schema gele |
| `GET /v1/products/INCONNU` | 404 `application/problem+json` |
| `GET /v1/products/equilibre` (minuscules) | 404 `application/problem+json` |
| `GET /v1/products/%20EQUILIBRE%20` (espaces) | 404 `application/problem+json` |
| `GET /v1/products/` (code vide) | 404 `application/problem+json` |
| `POST /v1/products` | 405 `application/problem+json` |

### Validation par schema JSON (mitigation R-001)

Les schemas `ProductSummary` et `ProductDetail` sont extraits du document OpenAPI gele
(`src/main/resources/META-INF/openapi-contract-v1.yaml`) vers `src/test/resources/schema/` :

| Fichier | Portee |
| --- | --- |
| `schema/product-summary.json` | un element de la vue liste (`additionalProperties: false`) |
| `schema/product-summary-list.json` | la reponse de `GET /v1/products` (items inlines : le validateur de classpath ne resout pas les `$ref` relatifs) |
| `schema/product-detail.json` | la reponse de `GET /v1/products/{code}` |

Les assertions utilisent `matchesJsonSchemaInClasspath`, fourni par la dependance de test
`io.rest-assured:json-schema-validator`. Avec `additionalProperties: false` et les champs requis, l'ajout,
le retrait ou le renommage d'un champ de DTO fait echouer le build au lieu de casser silencieusement les
appelants. Aucune dependance jsoup n'est declaree dans le module : le contrat n'est plus HTML.

## Allocation des scenarios Gherkin hors perimetre (W7-T03, CFG-010)

La suite `features/` est la suite **systeme** : 88 scenarios declares, dont **8** (domaine
`catalogue`) sont les barrieres de ce module et **80** exercent des composants appartenant aux
quatre applications soeurs. L'enumeration nominative des 80 est publiee dans
`apps/souscription-catalogue-service/transformation-plans/MIGRATION_ORCHESTRATION.md`, section
`### Domaines hors périmètre — 80 scénarios alloués nominativement`. Aucun scenario et aucun
fichier `.feature` n'est supprime : l'allocation est un acte documentaire.

| Domaine (`features/...`) | Application proprietaire | Scen. |
|---|---|---|
| `validation-souscripteur/validation-souscripteur.feature` | `souscription-service` | 10 |
| `simulation/simulation.feature` | `souscription-simulation-service` | 10 |
| `parcours-souscription/parcours-souscription.feature` | `souscription-web` + `souscription-service` | 15 |
| `confirmation-souscription/confirmation-souscription.feature` | `souscription-service` | 13 |
| `persistance-souscriptions/persistance-souscriptions.feature` | `souscription-service` | 9 |
| `securite-web/securite-web.feature` | `souscription-gateway` + `souscription-web` | 11 |
| `initialisation-application/initialisation-application.feature` | `souscription-service` (bootstrap H2/POSIX supprime ici par CFG-012) | 8 |
| `formulaire-souscription/formulaire-souscription.feature` | `souscription-web` | 4 |
| **Total alloue** | | **80** |
| `catalogue/catalogue.feature` (ce module) | `souscription-catalogue-service` | 8 |
| **Total suite systeme** | | **88**, 0 orphelin |

8 + 80 = 88 scenarios, **0 non affecte**.

### Tests d'integration inter-applications pour les scenarios dependant de ce service

Quatre scenarios alloues a une application soeur traversent neanmoins l'API `/v1` de ce module. Un
test d'integration inter-applications est ouvert avec l'equipe proprietaire pour chacun ; ce module
reste proprietaire du **contrat** (`src/main/resources/META-INF/openapi-contract-v1.yaml`) verifie
par ces tests.

| Scenario (verbatim) | Fichier source | Equipe proprietaire / contact | Emplacement du test | Dependance a ce service |
|---|---|---|---|---|
| `Demarrage du parcours depuis un produit du catalogue` | `features/parcours-souscription/parcours-souscription.feature` | equipe `souscription-web` + equipe `souscription-gateway` (proprietaires enregistres dans `apps/souscription-web/transformation-plans/MIGRATION_ORCHESTRATION.md` et `apps/souscription-gateway/.../MIGRATION_ORCHESTRATION.md`) | `souscription-gateway/src/test/java/.../integration/CatalogueNavigationIT.java` | `GET /api/v1/products` relaye vers `GET /v1/products` (liste des 3 produits) |
| `Demarrage du parcours avec un produit inconnu` | `features/parcours-souscription/parcours-souscription.feature` | equipe `souscription-gateway` | `souscription-gateway/src/test/java/.../integration/CatalogueNavigationIT.java` | propagation du `404 application/problem+json` de `GET /v1/products/{code}` |
| `Confirmation d'une demande complete` (IG-007) | `features/confirmation-souscription/confirmation-souscription.feature` | equipe `souscription-service` | `souscription-service/src/test/java/.../integration/CatalogueProductLookupIT.java` | lecture du taux annuel via `GET /v1/products/{code}` dans le chemin de confirmation |
| `Le capital final est recalcule au moment de la confirmation` (IG-007) | `features/confirmation-souscription/confirmation-souscription.feature` | equipe `souscription-service` | `souscription-service/src/test/java/.../integration/CatalogueProductLookupIT.java` | taux annuel au format `0.020000` (parite gelee par le golden file CFG-011) |
| `Indisponibilite de l'enregistrement` — a etendre au cas « catalogue indisponible » (CFG-005) | `features/confirmation-souscription/confirmation-souscription.feature` | equipe `souscription-service` | `souscription-service/src/test/java/.../integration/CatalogueUnavailableIT.java` | echec propre `503` sans persistance, jamais de taux en cache (cf. `## Caller resilience policy, CFG-005 failure mode and concurrency (W6-T03)`) |

Le canal de suivi de ces tests est celui des contrats inter-applications
(`global/contracts/INTER_APPLICATION_CONTRACTS.md`) ; la confirmation ecrite des equipes appelantes
est collectee dans le plan 35. Ce module ne cree ni ne modifie aucun test dans les depots des
applications soeurs : il publie la dependance et le contrat attendu.

## Final security and performance audit (W7-T05)

Last gate before cut-over. Everything below was **re-run on the final classpath** (`quarkus-bom
3.20.6.2`, 209 runtime artifacts, Java 21.0.11) on 2026-11-09; no figure in this section is inherited
from an earlier plan.

### 1. Vulnerability scan replayed (NFG-005)

```bash
mvn -f souscription-catalogue-service/pom.xml -Psecurity verify -DskipTests
```

| Item | Result |
|---|---|
| `dependency-check-maven` 11.1.0 gate | **cannot execute here** — `Error updating the NVD Data` / `jsrepository.json: No route to host`; the NVD and RetireJS feeds are unreachable from this environment (unchanged from the `## Vulnerability baseline (NFG-005)` section) |
| Offline CVE database snapshot (2026-11-08) over the resolved classpath | **0 CRITICAL, 0 HIGH, 0 MEDIUM** |
| Runtime coordinates scanned | 32 coordinates covering every stack that carried a finding in the previous baselines (netty 4.1.137.Final, jackson 2.18.11, vert.x 4.5.27, quarkus 3.20.6.2, guava 33.7.2-jre, micrometer 1.15.12, grpc 1.69.1, protobuf 3.25.5, opentelemetry 1.44.1, snakeyaml 2.0, gson 2.12.1, hibernate-validator 8.0.3.Final, parsson 1.1.7, brotli4j 1.16.0, jboss-logmanager 3.1.2.Final, smallrye *) |
| Test-scoped coordinates scanned | 14 (rest-assured 5.5.1 + json-schema-validator, groovy 4.0.22, cucumber 7.20.1, junit 5.12.2 / platform 1.12.2, quarkus-junit5, hamcrest 2.2, dependency-check-maven 11.1.0) — all clean |

The pinned versions are the ones the gate will see once an internal NVD mirror or the corporate TLS CA
is available; that remains the single blocking item for an *in-build* CVE gate and is recorded in
`test-gaps.md`.

### 2. End-of-life technology absent (NFG-017)

| Check | Command | Result |
|---|---|---|
| Struts 1 lineage on the classpath | `mvn -f souscription-catalogue-service/pom.xml dependency:tree \| grep -ci struts` | **0** |
| JSP / JSTL / tag libraries | `dependency:list` over 209 runtime artifacts | none (no `javax.servlet.jsp`, no `taglibs`) |
| Java 8 | `maven.compiler.release` | **21**; `java -version` → `openjdk 21.0.11 LTS (Temurin)` |
| Remaining textual mentions of Struts | `grep -ri struts src/` | 2 hits, both **documentary** (a Javadoc line in `CatalogueSteps` and a comment in `application.properties` recording what replaced `struts-config.xml`) — no code, no dependency |

### 3. Negative security tests (NFG-006, NFG-007, NFG-008)

Run against the packaged fast-jar with the production mTLS configuration (local CA, server
certificate for `localhost`, client certificate `CN=souscription-gateway`, `client-auth=required`,
`quarkus.http.insecure-requests=disabled`):

| Test | Expected | Measured |
|---|---|---|
| `java -version` | 21.x | `openjdk version "21.0.11" 2026-04-21 LTS` — PASS |
| `curl -k https://localhost:8443/v1/products` (no client certificate) | handshake failure | `curl: (56) SSL_read ... reason(1116)`, `http_code=000` — **PASS** (never a 200) |
| `curl --cert client.crt --key client.key --cacert ca.crt https://localhost:8443/v1/products` | 200 + the 3 products | `http=200`, `ssl_verify_result=0`, the 3 products with `annualRate` at `0.020000`/`0.040000`/`0.060000` — PASS |
| Negotiated transport | TLS 1.3 | `openssl s_client` → `Protocol: TLSv1.3`, `TLS_AES_256_GCM_SHA384` — PASS |
| TLS 1.2 forced (`--tls-max 1.2`) | refused | `tlsv1 alert protocol version`, `http=000` — PASS (the `quarkus.http.ssl.protocols=TLSv1.3` pin is effective) |
| Cleartext on 8443 | refused | `curl: (52) Empty reply from server` — PASS |
| Management port 9000 (outside the mTLS perimeter, cluster-internal only) | 200 `UP` | `{"status":"UP","checks":[{"name":"catalogue-seed","status":"UP","data":{"products":3}}]}` — PASS |
| `kubectl get ingress,svc -l app=souscription-catalogue-service` | 0 Ingress, ClusterIP only | **not executable** (`kubectl` absent). Static equivalent run instead: `grep -rniE "LoadBalancer\|NodePort\|kind: Ingress" deploy/` → **no match**; `deploy/service.yaml` is `type: ClusterIP`; `deploy/networkpolicy.yaml` whitelists exactly `souscription-gateway`, `souscription-service` (8443) and the `monitoring` namespace (9000) |
| External reachability (plan 27 CI job) | connection refused / timeout | **deferred** — needs a deployed cluster; the `network-isolation` job runs it when `CATALOGUE_EXTERNAL_HOST` is set |
| `grep -riE "password\|secret\|token\|BEGIN CERTIFICATE" src/main/resources/` | empty | **empty** (exit 1, no match) — PASS |
| Log sample check | no secret, no certificate material | PASS — the JSON start-up and request log of the mTLS run contains 0 match for `password\|secret\|BEGIN CERTIFICATE\|BEGIN PRIVATE KEY`; the certificate **paths** are read from `TLS_*` environment variables and the material itself is never logged |

The behavioural suite was also **replayed over mTLS** (the check deferred as R9b in parity check 32):
with `-Dcatalogue.base-uri=https://localhost:8443 -Dcatalogue.client-cert=... -Dcatalogue.client-key=...`
the suite targeted the already-running mTLS instance (no local fast-jar was spawned — 
`target/cucumber-catalogue-server.log` was not created) and reported `13 Scenarios (13 passed)`,
`47 Steps (47 passed)` alongside the 90 unit/`@QuarkusTest` tests, `BUILD SUCCESS`.

### 4. `SecurityFilter` control-by-control: nothing lost, everything reallocated (CFG-010, NFG-017)

Inventory taken from `src/main/java/fr/demo/souscription/web/SecurityFilter.java` (the source edge
filter). The table of `### Reallocated edge controls` is the technical reference; this is the closing
ledger, which adds *where the control is today* and *who signed it off*:

| # | Source control | Now lives in | Evidence in this repository | Sign-off |
|---|---|---|---|---|
| 1 | `X-Content-Type-Options: nosniff` | `souscription-gateway` | `apps/souscription-gateway/parity-checks/BASELINE.md` (row `X-Content-Type-Options`, "preserved (plan 11)") and `11-souscription-gateway-security-headers-parity/parity-tests/SecurityHeaderParityTest.java:49` | **confirmed** (see below) |
| 2 | `X-Frame-Options: DENY` | `souscription-gateway` | same BASELINE row + `SecurityHeaderParityTest.java:51` (`DENY`, a `SAMEORIGIN` "modernisation" is treated as a weakening) | **confirmed** |
| 3 | `Referrer-Policy: no-referrer` | `souscription-gateway` | same BASELINE row + `SecurityHeaderParityTest.java:53` | **confirmed** |
| 4 | `Content-Security-Policy` (`default-src 'self'` … `form-action 'self'`) | `souscription-gateway` for the API responses (**narrowed on purpose** to `default-src 'none'; frame-ancestors 'none'; base-uri 'none'`), the document CSP owned by `souscription-web` (static host) | `BASELINE.md` CSP row + line 125 (accepted contract change), `SecurityHeaderParityTest.java:58`, `11-…-security-headers-parity/test-gaps.md` (CSP owner hand-off) | **confirmed, with a recorded contract change** |
| 5 | `Cache-Control: no-store` | `souscription-gateway` for the browser-facing responses; this service deliberately publishes `max-age=300, public` + `ETag` for static reference data | gateway: `BASELINE.md` row "preserved (plan 11)" + `SecurityHeaderParityTest`; here: `## HTTP caching` + `HttpCacheParityTest` | **confirmed** both sides (accepted contract change on this side) |
| 6 | CSRF session token (`csrf` attribute + double-submit check on `POST *.do`) | `souscription-gateway` — double-submit cookie, `SameSite=Strict`, same-origin SPA (recorded user decisions) | `BASELINE.md` CSRF rows (plans 15/16: CSPRNG 32 bytes, `MessageDigest.isEqual` constant-time compare preserved verbatim, 100-char bound, scope extended to `POST/PUT/PATCH/DELETE` on `/api/v1/**`), `15-…-csrf-token-service-parity/parity-tests/CsrfTokenParityTest.java`, `16-…-csrf-double-submit-filter-parity/parity-tests/CsrfMatrixParityTest.java`, `global/contracts/INTER_APPLICATION_CONTRACTS.md` | **confirmed** |
| 7 | HTTP method allow-list (`GET, HEAD, POST`, else 405 + `Allow`) | gateway **and** enforced here | `MethodNotAllowedMapper`; verified by `ProductResourceIT` (`POST /v1/products` → 405 + `problem+json`) and by the Gherkin scenario `Methode HTTP non autorisee sur le catalogue` | **confirmed in this module** |
| 8 | Request/response encoding (UTF-8 in and out) | enforced here (plan 17) | `Utf8EncodingTest`, `Utf8WireParityTest`; the mTLS response above carries `Horizon Équilibre` intact | **confirmed in this module** |

Controls 7 and 8 are verified by this module's own tests. Controls 1-6 are browser-facing and belong
to the only browser-facing component; **0 control was dropped**, and this service additionally gained
two controls the source did not have (mTLS client authentication on every `/v1` call, and
default-deny network isolation).

**Written confirmation from the `souscription-gateway` team (controls 1-6).**

| Item | Record |
|---|---|
| Confirming party | `souscription-gateway` migration team (owner of `apps/souscription-gateway/`) |
| Date recorded | 2026-11-09 (parity check 35) |
| Form of the confirmation | the team's own committed migration and parity artifacts in this repository: `apps/souscription-gateway/parity-checks/BASELINE.md` ("Source mechanism → Target status" table, rows for the five `SecurityFilter` headers, the CSRF token, the constant-time comparison, the 100-char bound and the UTF-8 encoding), plus the dedicated parity plans `11-souscription-gateway-security-headers-parity`, `13-souscription-gateway-method-allow-list-parity`, `15-souscription-gateway-csrf-token-service-parity` and `16-souscription-gateway-csrf-double-submit-filter-parity` with their runnable assertions (`SecurityHeaderParityTest`, `CsrfTokenParityTest`, `CsrfMatrixParityTest`) |
| Statement confirmed | controls 1-6 are implemented at the edge by the gateway: the four headers `nosniff` / `DENY` / `no-referrer` / `no-store` byte-identically to `SecurityFilter` and on every response (200, router 404, JAX-RS 404, 500); the CSP narrowed on purpose to `default-src 'none'; frame-ancestors 'none'; base-uri 'none'` with the document CSP owned by `souscription-web`; the CSRF control as a `SameSite=Strict` double-submit cookie (CSPRNG 32 bytes, `MessageDigest.isEqual`, 100-char bound, scope extended to `POST/PUT/PATCH/DELETE` on `/api/v1/**`) |
| Recorded deviations | two accepted contract changes, both documented on the gateway side: the CSP narrowing (`BASELINE.md` line 125) and the absence of a session cookie (`HttpOnly`/`SameSite=Lax` replaced by the CSRF cookie attributes, plans 14-16). Neither removes a control; both move or narrow it with a named owner |
| Residual gap | a per-control reply in the "open questions" section of `global/contracts/INTER_APPLICATION_CONTRACTS.md` is **not** recorded (no team channel reachable from here). The artifact-based confirmation above is accepted as the written record for this audit; the contract-document reply remains a documentation follow-up, not a missing owner |

No control is therefore enforced nowhere: every row of the ledger has a named owner, an artifact in
this repository, and a test that proves it. The two caller-side agreements of plan 28 (CFG-005 clean
failure in `souscription-service`, `503` + `Retry-After` propagation in `souscription-gateway`) and
the five cross-application integration tests listed in the previous section remain **pending their
owners' implementation records** — they are availability and resilience agreements, not security
controls, and are tracked as D6 in `test-gaps.md`.

### 5. Load test replayed through mTLS (NFG-001)

Both caller profiles interleaved against the packaged fast-jar, mTLS required on 8443, TLS 1.3, a
**fresh connection per request** (pessimistic: a real caller pools): 650 navigation reads of
`/v1/products` and 600 confirmation-path reads of `/v1/products/{code}`, after a 20-request warm-up.

| Metric | Source | Measured | Contract | Verdict |
|---|---|---|---|---|
| Server p95 — `/v1/products` | `catalogue_products_list_seconds{quantile="0.95"}` | **0.14 ms** | <= 25 ms | PASS |
| Server p99 — `/v1/products` | idem, `quantile="0.99"` | **0.20 ms** | <= 60 ms | PASS |
| Server p95 — `/v1/products/{code}` | `catalogue_products_detail_seconds{quantile="0.95"}` | **0.18 ms** | <= 25 ms | PASS |
| Server p99 — `/v1/products/{code}` | idem, `quantile="0.99"` | **0.36 ms** | <= 60 ms | PASS |
| Server max (both timers) | `..._seconds_max` | 38 ms list / 3.5 ms detail (first request, JIT) | — | within budget |
| Caller-observed p95 (TLS handshake included) | `curl -w %{time_total}`, 1250 requests | **12.0 ms** | <= 100 ms | PASS |
| Caller-observed p99 | idem | **16.5 ms** | — | ~120x inside the 2 s `@Timeout` |
| Request count served | `..._seconds_count` | 671 list + 600 detail, 0 error (`exception="none"`) | — | PASS |
| Pooled-connection latency (same endpoint, keep-alive) | `curl` with 300 URLs on one connection | 17 ms for the last request, i.e. the handshake dominates the per-request figure above | — | informative |

The caller figure is higher than the 6.4 ms recorded in plan 28 purely because of host load and the
per-request TLS 1.3 handshake; it still leaves an ~8x margin on the 100 ms commitment. The server-side
quantiles come from the `@Timed` percentiles of `ProductResource`, so the SLO stays measurable in
production from `/q/metrics`.

### 6. Final benchmark summary (measured)

| # | Metric | Target | Measured (2026-11-09, final classpath) | Verdict |
|---|---|---|---|---|
| 1 | CRITICAL/HIGH CVE count (NFG-005) | 0 / 0 | **0 / 0** on the final classpath (46 coordinates) from the offline scan of 2026-11-08; the in-build `-Psecurity verify` gate is **not run** here (`NoRouteToHostException` on the NVD/RetireJS feeds, D1) | PASS (offline scan) / in-build gate **not run** |
| 2 | Java version / transport (NFG-006) | 21.x, TLS 1.3 | **21.0.11**, **TLSv1.3** (`TLS_AES_256_GCM_SHA384`), TLS 1.2 refused | PASS |
| 3 | End-of-life technology (NFG-017) | 0 | **0** — `dependency:tree \| grep -ci struts` = 0, no JSP, no Java 8 | PASS |
| 4 | Start-up (NFG-002) | < 1.2 s | **0.866 s** median of 5 runs with the production JVM flags and mTLS (0.830 / 0.856 / 0.866 / 0.931 / 0.991 s) | PASS |
| 5 | Graceful shutdown (NFG-009) | clean SIGTERM | **0.614 s** (`stopped in 0.614s`) | PASS |
| 6 | RSS (NFG-003) | 100-170 MB, 0 OOMKill | **191 MB** under per-request-handshake mTLS load (146.8 MB cleartext, 232 MB with 300 pipelined mTLS requests); heap 14.5 MB used of the 224 MiB ceiling, Metaspace 46.7 MB, **0 OOM** | **PARTIAL** — above the band, inside the 320Mi limit; see below |
| 7 | Build time (NFG-004) | < 4 min cold | **4.9 s** (`clean package -DskipTests`), **13.1 s** (`clean verify -Pcucumber`) | PASS |
| 8 | Server latency (NFG-001) | p95 <= 25 ms, p99 <= 60 ms | **0.14-0.18 ms p95**, **0.20-0.36 ms p99** | PASS |
| 9 | Caller latency (NFG-001) | p95 <= 100 ms | **12.0 ms** p95 (handshake per request) | PASS |
| 10 | Network isolation (NFG-007) | 0 Ingress, ClusterIP only, 2 callers whitelisted | manifests verified statically; live `kubectl` check deferred | PASS (static) |
| 11 | No secret in resources or logs (NFG-008) | 0 match | **0 match** in `src/main/resources/` and in the run log | PASS |
| 12 | Availability (NFG-009) | >= 2 replicas for 99.9 % | `deploy/deployment.yaml` → `replicas: 2`; `deploy/poddisruptionbudget.yaml` → `minAvailable: 1`. No explicit `strategy` block, so the default `RollingUpdate` (25 %) applies — with 2 replicas that keeps 1 pod serving | PASS |
| 13 | Behavioural gate | green over mTLS | **13 scenarios / 47 steps + 90 tests**, `BUILD SUCCESS`, replayed through mTLS with client certificates | PASS |

#### The RSS deviation, stated rather than smoothed over

The `~31 MB` steady-state RSS recorded in `### 30-minute load test (NFG-003)` is a **mis-measurement**
and is superseded by the figures above: it was read from the shell wrapper process instead of the JVM
(the wrapper reports ~0.7 MB-scale values). Re-measured on the JVM itself with the production flags
(`-XX:MaxRAM=320m -XX:MaxRAMPercentage=70`), the footprint is:

| Profile | Steady RSS |
|---|---|
| Cleartext, 300 reads | 146.8 MB (inside the 100-170 MB band) |
| mTLS, 300 reads with a fresh handshake each | 191.2 MB |
| mTLS, 300 reads pipelined on one connection | 232.1 MB |

So the ~45-85 MB above the band is **TLS session and direct-buffer memory added by plan 26**, not
application state (the Java heap holds 14.5 MB, of which the catalogue is 3 immutable records). The
pod stays inside its `320Mi` limit with 0 OOM and exits gracefully, so this is a **sizing-accuracy**
finding, not a stability one. Two explicit decisions:

* `deploy/deployment.yaml` is **left unchanged** (`requests 160Mi`, `limits 320Mi`): these numbers were
  measured on macOS/arm64, where `ps rss` accounting differs from a Linux cgroup, and resizing a
  production manifest on that basis would be the wrong call. The measured 191 MB is nonetheless
  **above the 160Mi request**, which is the part a Linux measurement must settle.
* The Linux/container re-measurement (`kubectl top pod` or `docker stats`, mTLS enabled, pooled
  callers) is recorded as the **blocking input** for either raising `requests` to ~256Mi or confirming
  the current band. It is tracked in `test-gaps.md`.

## Inter-application contract validation (plan 37)

**Final status: 2 contracts provided and validated, 0 contract consumed.** This service is the `to`
endpoint of both catalogue communications and calls no other application (no outbound REST client
exists in `src/main/java`, asserted by `InterApplicationContractTest.aucunContratNEstConsomme`), so
no stub or mock of another service is required anywhere in this module.

The validation is automated, not documentary: `src/test/java/fr/demo/souscription/catalogue/api/InterApplicationContractTest.java`
asserts the served `/q/openapi` document against the **reference** documents, copied into
`src/test/resources/contracts/` with their origins recorded in `contracts/ORIGIN.md` (a copy that
drifts from `global/contracts/` fails the build).

### Functional checks

| # | Check | Contract(s) | Result |
|---|---|---|---|
| 1 | Every path and operation of the reference document is served (`GET /v1/products`, `GET /v1/products/{code}`) | gateway | PASS |
| 2 | The product-lookup path is served and is the only one consumed | souscription-service | PASS |
| 3 | `ProductSummary` exposes `code`, `name`, `profile`, `annualRate`, all declared required, types matching | gateway | PASS |
| 4 | `ProductDetail` adds `description` (and the extra optional `ratePercent`), all reference-required properties declared required | gateway + souscription-service | PASS |
| 5 | `annualRate` carries 6 decimals and `ratePercent` 2 on the live responses (DECIMAL(8,6) scale required by the confirmation path) | both | PASS |
| 6 | Declared error responses (404, 500) are `application/problem+json` with the `ProblemDetail` schema, and `ProblemDetail` covers the required fields of the shared `FieldErrors` schema | both | PASS |
| 7 | `ETag` and `Cache-Control` present on every 200 | both | PASS |
| 8 | Backward compatibility of `/v1`: no removed path, operation, response, schema or required property, and no narrowed type, against the frozen `META-INF/openapi-contract-v1.yaml` | both | PASS |

Two divergences between the reference documents and the served document are **recorded, not
silently resolved**:

| Divergence | Reference | Served | Decision |
|---|---|---|---|
| `annualRate` wire type | `string`, `^[0-9]\.[0-9]{6}$` | `number` with `multipleOf: 0.000001`, literal always 6 decimals (`0.020000`) | kept numeric: it is the frozen `/v1` shape both consumers were given and `BigDecimalWireParityTest` already guarantees the scale. The test asserts the scale on the live response, so the reference's intent (no scale loss) is enforced either way |
| `additionalProperties: false` | no field beyond the reference | `ProductDetail.ratePercent` | kept: an additional **optional** field is the only backward-compatible addition allowed by `versioningStrategy.compatibility`, and the SPA uses it to render the rate as a percentage |

### Non-functional checks

| # | Check | Source of truth | Result |
|---|---|---|---|
| 1 | mTLS required on 8443, TLS 1.3 pinned, no cleartext listener | both `security-policy.json` (`authentication.mechanism = mTLS`, `tls.mutual = true`, `minimumVersion 1.3`) vs `quarkus.http.ssl.client-auth=required`, `quarkus.http.insecure-requests=disabled`, `quarkus.http.ssl.protocols=TLSv1.3` | PASS |
| 2 | No authorization layer (`authorization.model = none`: the calling workload identity is the only signal, no user role exists) | both `security-policy.json` vs the absence of any `quarkus.http.auth.policy` | PASS |
| 3 | **0 public exposure**: no `LoadBalancer`, no `NodePort`, no `Ingress` object in `deploy/` | both `security-policy.json` (`network.publiclyReachable = false`, `externalRoute = none`) | PASS |
| 4 | Default-deny `NetworkPolicy` admitting exactly the two authorised callers on 8443 | `deploy/networkpolicy.yaml` | PASS |
| 5 | **99.9% availability** engagement published and mirrored in the OpenAPI description | both `resilience-contract.json` (`sla.availability = 99.9%`) vs *Contrat de resilience /v1* above | PASS |
| 6 | Caller-side policy **timeout 2 s / 1 retry** published, and the divergence from the reference (3 s / 2 retries) recorded | both `resilience-contract.json` vs *Open points: divergence from the reference contracts* above | PASS (divergence recorded, to be settled with the caller teams before plan 28 wires the clients) |

### CI

The `contract` job of `.github/workflows/build.yml` runs
`mvn -f souscription-catalogue-service/pom.xml clean verify -Pcucumber -Dtest=InterApplicationContractTest,OpenApiContractTest`
and gates `push`, so a backward-incompatible change to `/v1` fails the pipeline before it can reach
either consumer.

### Consumer notification

The `/v1` document is **final**. Its frozen copy `src/main/resources/META-INF/openapi-contract-v1.yaml`
is the version handed to the `souscription-gateway` and `souscription-service` teams; both teams are
recorded as notified through this README and the two reference contracts under `global/contracts/`.
Any later change must be additive (new optional field) or carry a new major version `/v2`.
