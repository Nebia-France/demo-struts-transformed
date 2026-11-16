# Consumer contract validation — souscription-web -> souscription-gateway (plan 45)

souscription-web is the **consumer** of the single inter-application contract
`souscription-web-to-souscription-gateway` (OpenAPI 3.1.0, provider `souscription-gateway`). This
record is guarded by `npm run check:contract` (wired into `npm run lint`): each of the four `## `
sections below must exist and carry a `**Result:**` line, the resilience section must state its
measured p95/p99, and the production configuration must keep the gateway stub switched off.

| Item | Value |
| --- | --- |
| Contract | `contracts/openapi.yaml` (vendored, byte-identical to `global/contracts/souscription-web-to-souscription-gateway/openapi.yaml`), `contracts/resilience-contract.json`, `contracts/security-policy.json` |
| Suite | `npm run contract:consumer` -> `e2e/contract/consumer-contract.spec.ts` (17 tests, Playwright project `contract`) |
| Schema validation | Ajv 8 (JSON Schema 2020-12) compiled from the contract responses, `$ref`s resolved onto the vendored `shared-schemas/*.json` (byte-identical to `global/contracts/shared-schemas`, also enforced by `contract:check`) |
| Measured report | `e2e/reports/contract-consumer.json` (written by every run; CI artifact) |
| Run date | 2026-11-14 |
| Target of the recorded run | **gateway double** (`e2e/support/fake-gateway.ts`) on the production build served by `scripts/perf-server.mjs` (`http://localhost:4300`) |

**Target status.** No deployed `souscription-gateway` answers on the SPA origin yet. The suite
therefore ran against the contract-shaped gateway double, and every test carries the annotation
`target: gateway-double`. The suite is target-agnostic: `CONTRACT_BASE_URL=<origin of the SPA and
the gateway> npm run contract:consumer` runs the same 17 tests against the provider without any
interception, and the tables below must then be re-filled from `e2e/reports/contract-consumer.json`.
Until that run is recorded, the measured latency figures describe the consumer and the double, not
the provider.

## 1. Operations

`npm run contract:check` exits 0: the vendored copy is byte-identical to the published contract and
declares the 10 consumed operations. Each operation is then exercised once, in wizard order, from a
document of the SPA origin (same-origin `fetch`, `XSRF-TOKEN` cookie echoed in `X-CSRF-Token`), and
its body is validated against the schema the contract declares for the returned status. Every
monetary field (`initialAmount`, `monthlyAmount`, `contributed`, `finalCapital`, and the
`annualRate`) must be a JSON string.

| # | operationId | Method and path | Status | Schema | Monetary fields (all strings) | Measured |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `issueCsrfToken` | `GET /csrf-token` | 200 | inline `{ token }` | 0 | 3.3 ms |
| 2 | `listProducts` | `GET /products` | 200 | `ProductSummary[]` | 3 | 1.6 ms |
| 3 | `getProduct` | `GET /products/{code}` | 200 | `ProductDetail` | 1 | 1.4 ms |
| 4 | `simulate` | `POST /simulations` | 200 | `SimulationResult` | 4 | 1.8 ms |
| 5 | `startDraft` | `POST /drafts` | 201 + `Location` | `DraftView` | 1 | 1.4 ms |
| 6 | `getDraft` | `GET /drafts/{draftId}` | 200 | `DraftView` | 1 | 1.4 ms |
| 7 | `putDraftSubscriber` | `PUT /drafts/{draftId}/subscriber` | 200 | `DraftView` | 1 | 1.7 ms |
| 8 | `putDraftSimulation` | `PUT /drafts/{draftId}/simulation` | 200 | `DraftView` | 5 | 1.6 ms |
| 9 | `confirmDraft` | `POST /drafts/{draftId}/confirmation` | 201, replay 200 same reference | `SubscriptionConfirmation` | 5 | 1.6 ms |
| 10 | `getSubscription` | `GET /subscriptions/{reference}` | 200 | `SubscriptionConfirmation` | 5 | 1.4 ms |

Contract deviations the suite found in the test doubles, fixed in this plan:

- `issueCsrfToken` answered `{ "expiresIn": 3600 }` in `fake-gateway.ts`, `journey-helpers.ts` and
  `perf-server.mjs`; the contract requires `{ "token": <cookie value, >= 32 chars> }` with
  `additionalProperties: false`. All three doubles now return the token.
- `fake-gateway.ts` served `annualRate` as `"0.02"`; the contract pattern is `^[0-9]\.[0-9]{6}$`
  (DECIMAL(8,6)). The reference products now carry `"0.020000"`, `"0.040000"`, `"0.060000"`.

The SPA itself never read the `issueCsrfToken` body (it reads the cookie), so no SPA code changed.

**Result:** PASS — 10/10 operations validated against the contract schemas (`npm run
contract:consumer`, 17 passed), every monetary field a string; target: gateway double.

## 2. Resilience

Asserted against `contracts/resilience-contract.json`. The latency is measured client-side from the
SPA origin over every call of the run (the 10 operations, the idempotent replay, the CSRF probe and
60 catalogue reads). The timeout, retry and bulkhead checks drive the **production bundle**: a
client-side fault injection holds `GET /api/v1/products` for 12 s in front of the target.

| Property | Contract | Measured | Verdict |
| --- | --- | --- | --- |
| Client timeout | 10 s | aborted after **10005 ms** | PASS |
| Retries | 0 | **1** attempt, **0** retry (2 s observation window after the abort) | PASS |
| Concurrent calls | at most 6 | max in flight **1** during the timeout journey; a burst of **6** concurrent `getProduct` all answered 200 | PASS |
| Latency p50 | 150 ms | **1.0 ms** (72 samples) | PASS |
| Latency p95 | <= 600 ms | **p95 1.7 ms** | PASS |
| Latency p99 | <= 1500 ms | **p99 3.3 ms** (max 3.3 ms) | PASS |
| Availability objective | 99.9% | not measurable from a single run: owned by the provider SLO (Micrometer timers, `sla.measurement`); 72/72 calls answered with the contract status during the run | recorded |

The bulkhead (6) and the 10 s timeout are implemented by `src/app/core/http/timeout.interceptor.ts`
and `src/app/core/http/concurrency.ts`, and unit-tested there; `npm run check:no-retry` forbids any
retry operator in `src/`. The latency figures above are those of the gateway double (no network
hop); they must be re-measured with `CONTRACT_BASE_URL` against the deployed provider.

**Result:** PASS — timeout 10 s, 0 retry, <= 6 concurrent calls, p95 1.7 ms <= 600 ms, p99 3.3 ms <=
1500 ms; target: gateway double.

## 3. Security

Asserted against `contracts/security-policy.json`.

| Property | Policy | Measured | Verdict |
| --- | --- | --- | --- |
| Issuing endpoint | `GET /api/v1/csrf-token` | 200, `XSRF-TOKEN` cookie set, body `token` equal to the cookie value | PASS |
| Cookie name / header name | `XSRF-TOKEN` / `X-CSRF-Token` | `XSRF-TOKEN` / `X-CSRF-Token` (the mutations of section 1 succeed with the echoed header) | PASS |
| Mutation without the token | 403 | `POST /drafts` without `X-CSRF-Token`: **403** `application/problem+json`, body valid against `FieldErrors` | PASS |
| `SameSite` | `Strict` | `Strict` | PASS |
| `HttpOnly` | false (the SPA must read it) | `false` | PASS |
| `Secure` | true | asserted only on an `https:` origin; the recorded run is `http://localhost` | pending live run |
| Cross-origin headers | none (same origin, OD-004) | **0** cross-origin header on the responses (observed headers: `cache-control`, `content-length`, `content-type`, `location`) | PASS |

`npm run check:no-cors` keeps `src/` and `deploy/` free of any cross-origin configuration, and
`npm run check:csrf` pins the cookie and header names in the SPA.

**Result:** PASS — double-submit CSRF honoured (403 without the token), `SameSite=Strict`, cookie not
`HttpOnly`, no cross-origin header; `Secure` to be confirmed on the HTTPS live run.

## 4. Versioning

| Check | Expected | Observed | Verdict |
| --- | --- | --- | --- |
| Public prefix used by the SPA | `/api/v1` | `API_BASE_URL = '/api/v1'` (`src/app/api/api-base-url.ts`); the three `servers` of the public contract end in `/api/v1` | PASS |
| Internal prefix between services | `/v1` | the five internal contracts of `global/contracts/` (`souscription-gateway-to-*`, `souscription-service-to-*`) declare `https://<service>.<env>.svc.cluster.local:8443/v1` | PASS |
| Public contract version | `1.0.0` within `v1` | `info.version: 1.0.0` | PASS |
| Changes in `v1` since publication | backward-compatible only | `git diff e9bd57cd HEAD` on the public contract and the shared schemas: 2 description-only lines (plan 44); no path, operation, status, required field or type changed | PASS |
| OpenAPI publication | public document with the gateway, internal ones with each service | public: `global/contracts/souscription-web-to-souscription-gateway/openapi.yaml`, vendored at `contracts/openapi.yaml`; internal: `global/contracts/<communication>/openapi.yaml` | PASS |

Any breaking change (removing or renaming a field, an operation or a status, tightening a pattern)
requires `/api/v2`; `npm run contract:check` fails as soon as the vendored copy diverges, and
`npm run api:verify` as soon as the generated models drift.

**Result:** PASS — `/api/v1` public for the SPA, `/v1` internal between services, only
backward-compatible (description-only) changes in `v1`.

## Gateway stub

`NG_APP_USE_GATEWAY_STUB` is `"false"` in the `production` (and `local`) configuration of
`angular.json` and `"true"` only in `development`, which alone copies `public/mockServiceWorker.js`.
`src/main.ts` imports the msw worker behind that build-time constant, so the production bundle drops
it: `grep -rl "msw" dist/souscription-web/browser` returns nothing after `npm run build`, asserted
by the last test of `npm run contract:consumer` and by `npm run check:contract` for the flag. The
stub stays available for `npm start` (development) only.

## Parity re-verification (plan 45 parity)

Re-run on 2026-11-14 of the six parity steps, with no remediation required. The target is still the
**gateway double**, because no deployed `souscription-gateway` answers on the SPA origin yet (see
the target status above).

| Step | Command | Observed | Verdict |
| --- | --- | --- | --- |
| 1 Contract identity | `npm run contract:check`; `diff contracts/openapi.yaml ../../global/contracts/souscription-web-to-souscription-gateway/openapi.yaml` | exit 0; no difference | PASS |
| 2 Operations, string money | `npm run contract:consumer` | 17 passed; 10/10 operations schema-valid, every monetary field a string | PASS (double) |
| 3 Stub retired | `grep -rl "msw" dist/souscription-web/browser`; production `define` | no match (exit 1); `NG_APP_USE_GATEWAY_STUB` = `"false"` | PASS |
| 4 Resilience | `e2e/reports/contract-consumer.json` | abort at 10007 ms (10 s), 1 attempt / 0 retry, max in flight 1, 6 concurrent served; p95 1.5 ms, p99 5.2 ms (72 samples); 99.9% availability is owned by the provider SLO | PASS (double) |
| 5 Security | same report; `grep -rn "Access-Control-"` | `POST /drafts` without `X-CSRF-Token` -> 403; `XSRF-TOKEN` SameSite=Strict, not HttpOnly; 0 cross-origin header; no `Access-Control-` in the app | PASS (double); `Secure` pending HTTPS live run |
| 6 Versioning and guard | `npm run check:contract` | exit 0, 4 sections, stub flag `"false"`; `/api/v1` public, `/v1` internal, backward-compatible only | PASS |

Build gate: `npm ci && npm run lint && npm run build && npm test && npm run e2e` exit 0 (242/242
unit tests, 128/128 cucumber rows, 88/88 scenario rate). Per R2c, the provider-side validation is
still an unmet entry criterion until `CONTRACT_BASE_URL` points at the deployed gateway.
