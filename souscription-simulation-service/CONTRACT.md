# souscription-simulation-service — provider contract and latency budget

This service is the **provider** of the two frozen contracts
`global/contracts/souscription-gateway-to-souscription-simulation-service/` and
`global/contracts/souscription-service-to-souscription-simulation-service/`.
Those files are the source of truth: every figure below is cross-checked against
both of them and **the contract wins on any divergence**. Nothing in this
document may be changed without changing the contract first.

## Published SLA

```
Published SLA: availability 99.9%; p50 15 ms, p95 60 ms, p99 400 ms
Server timeout 2.5 s (< 3 s caller timeout); bulkhead 20 concurrent calls, queue 40
400 (validation) never retried; 429 never retried; 502/503/504 retryable once
Endpoint: POST https://souscription-simulation-service.<env>.svc.cluster.local:8443/v1/simulations
Transport: TLS 1.3, client-auth required, accepted identities: souscription-gateway, souscription-service
```

| Commitment | Published value | Source |
| --- | --- | --- |
| Availability | 99.9 % | `sla.availability` of both contracts (OD-009, confirmed) |
| Response time | p50 15 ms, p95 60 ms, p99 400 ms | `sla.responseTime` of both contracts |
| Internal p95 objective | ≤ 50 ms, p99 ≤ 120 ms | NFG-001, stricter than the contract; drives the alert rules in `deploy/alerts/simulation-alerts.yml` |
| Caller timeout | 3 s | `timeout.value` of both contracts |
| Server timeout | 2 500 ms | `@Timeout` on `SimulationResource.compute`, strictly below the 3 s caller timeout |
| Bulkhead | 20 concurrent calls, waiting queue 40 | `bulkhead.maxConcurrentCalls = 20` of the souscription-service contract is the **server-side** limit, the one implemented by `@Bulkhead(value = 20, waitingTaskQueue = 40)`. The gateway contract's **30** is a **caller-side concurrency allowance** (how many in-flight calls that caller may hold), not a server capacity promise: per `BASELINE.md` §5 the two figures are not a contradiction to reconcile. Publishing 30 as the server figure would promise a concurrency the bulkhead does not grant, so callers would see unexplained `503`s |
| Overload response | `503 application/problem+json`, never 500, never 429 | `BulkheadExceptionMapper` / `TimeoutExceptionMapper` |
| Rate limit | 100 req/s, burst 200, **per calling workload identity**; excess is `429 application/problem+json` | `rateLimiting` of both frozen security policies, enforced by `RateLimitFilter` |
| Retry (caller side) | at most **1** retry, exponential backoff with full jitter, 100 ms base, 1 s cap | `retry` of both contracts |
| Retryable | connection failure, read timeout, `502`, `503`, `504` | `retry.retryableConditions` |
| Never retried | any `4xx`, including `400` validation failures and `429` | `retry.nonRetryableConditions` |
| Circuit breaker (caller side) | volume 20, failure ratio 0.5, delay 10 s, success threshold 2 | `circuitBreaker` of both contracts |
| Measurement | Micrometer timers on the provider (`http_server_requests_seconds_*`, `simulation_calculate_seconds_*`) compared with the consumer-side timeout | `sla.measurement` |

The single retry is safe because the computation is **pure and side-effect
free**: it writes nothing, holds no lock and has no datasource.

`429` is reserved for rate limiting (plan 26) and is never emitted by the
bulkhead; the queue is always finite, because an unbounded queue would turn fast
rejection into unbounded latency.

## Idempotency — the basis of the single retry

The contracted retry is only sound if a replay is indistinguishable from the
first call. That is a **property of this service, asserted on every build** by
`SimulationIdempotencyTest`, not an assumption of the caller:

- ten replays of the same request body return **byte-identical** bodies and an
  identical `Content-Type`;
- no `Set-Cookie`, no `Location`, no ETag: nothing lets a replay be routed or
  resolved differently;
- no side effect of any kind — this service owns no datastore, no queue and no
  mutable business state, so there is nothing a replay could advance. The only
  thing a call moves is the request-counting instrumentation of waves 4 and 5
  (`simulation_calculate_seconds_count`, the input-digest counters, the span and
  the audit line), and the test pins that none of it reaches the payload: a
  counter visible in the response would make the N-th answer differ from the
  first and silently void the retry allowance.

Consequence for the callers: a `502/503/504` may be retried once, with no
deduplication key, no idempotency header and no server-side replay cache.
Conversely a `400` and a `429` carry no retry allowance at all.

## Rate limiting

| Property | Value |
| --- | --- |
| Limit | 100 requests/s, burst 200 |
| Scope | per calling workload identity (the SPIFFE identity of the peer certificate, resolved by `WorkloadIdentityFilter`) |
| Excess | `429 application/problem+json`, **no `Retry-After`** — the frozen contracts list `429` under `retry.nonRetryableConditions`, so no header may invite a retry |
| Mechanism | token bucket in `RateLimitFilter`; an excess call is refused immediately, never queued |
| Configuration | `simulation.ratelimit.requests-per-second` / `simulation.ratelimit.burst` (`SIM_RATE_LIMIT_RPS` / `SIM_RATE_LIMIT_BURST`), seeded with the contract figures |
| Observability | `simulation_ratelimit_rejected_total` (untagged), next to `ft_bulkhead_calls_total` and `worker_pool_*` |

The limit is enforced in process because this topology has no service mesh. If a
mesh is introduced, the policy moves there unchanged and these two keys become
its configuration; the two rejection semantics must stay distinct either way —
**`503` for capacity (retryable once), `429` for rate (never retried)**.
`FaultInjectionTest` asserts the three degradations and the metrics that make
them diagnosable: the server timeout answers inside 2 500 ms and below the 3 s
caller timeout, bulkhead overflow is `503`, rate-limit excess is `429`.

## Transport

| Property | Value |
| --- | --- |
| Scheme / port | `https` on `8443` — `quarkus.http.insecure-requests=disabled`, so there is no plaintext listener to downgrade to |
| TLS | `TLSv1.3` only; cipher suites `TLS_AES_256_GCM_SHA384`, `TLS_AES_128_GCM_SHA256` |
| Client authentication | `required` — a caller with no client certificate is rejected **in the handshake**, never with an application 401/403 |
| Accepted workload identities | `spiffe://horizon-souscription/ns/horizon/sa/souscription-gateway`, `spiffe://horizon-souscription/ns/horizon/sa/souscription-service` (`WorkloadIdentityFilter`); every other PKI-valid identity gets `403 problem+json` without disclosing the list |
| Reachability | `ClusterIP` only, no Ingress and no Route; the management port 9000 is unpublished |

## What changed, and the rule that replaces it

In the legacy monolith the projection was recomputed **inside the same unit of
work** as the `subscriptions` insert:
`SubscriptionAction` (`/confirmer`) called `SubscriptionService.confirm(draft)`,
which called `SimulationService.calculate(...)` in-process and passed the
**recalculated** `Simulation` straight to `SubscriptionRepository.save(...)`,
where the insert ran on a single JDBC connection with `autoCommit(false)`. The
draft's own `Simulation` — the one the user had seen — was never persisted, and a
transport failure between the computation and the insert was impossible by
construction.

Now the computation crosses the network (confirmed topology decision: *keep it
remote*). Two rules preserve the legacy guarantee that **the server alone
determines the sold capital**:

1. **No client fallback.** The confirmation must **never** fall back to a
   client-supplied `SimulationResult`, nor to the figures displayed in the
   wizard. A `SimulationResult` arriving in a confirmation request body is input
   to be discarded, never a value to be persisted.
2. **No local recompute.** The caller must **never** recompute the projection
   locally when this service is unavailable. A second implementation of the
   formula would diverge from this one on the first rate or rounding change, and
   the capital sold would then depend on which service answered.

The contracted behaviour when this service is unavailable is therefore the one
frozen in `souscription-service-to-souscription-simulation-service`:

> Fail the confirmation with a retryable 503 and persist nothing. Never fall back
> to the client-supplied `SimulationResult` and never recompute locally.

Nothing is persisted, so the user's retry — same `confirmationKey` — still
creates exactly one subscription, exactly as the idempotent legacy insert did.

## Availability implication: the objective is now a product

The legacy confirmation path had **one** availability term, because the
projection could not fail independently of the insert. The confirmation path now
has **two**:

```
A(confirmation) ≈ A(souscription-service) × A(souscription-simulation-service)
```

Two independent services at 99.9 % yield ≈ **99.8 %** on the composed path — the
99.9 % objective of the confirmation path is not met by each service meeting
99.9 % in isolation. Consequences, all of them already in place rather than
aspirational:

- this service runs **≥ 2 replicas** (stateless, no affinity — measured at 2 and
  4 replicas with 0 failed request, see `README.md`);
- the single contracted retry on `502/503/504` recovers the single-replica
  failure case and is what brings the composed figure back toward 99.9 %;
- both terms are monitored from the plan-21 metrics
  (`http_server_requests_seconds_*` by `outcome`/`status`) and alerted by
  `deploy/alerts/simulation-alerts.yml`; the owner of the composed objective is
  the confirmation path, not either service alone, so a 99.9 % report from this
  service alone is **not** evidence that the objective is met.

## Measured latency budget

Asserted on every build by the blocking `latency-budget` pipeline stage
(`.ci/templates/latency-budget.sh` + `latency-budget.py`), which drives
`POST /v1/simulations` on the production transport (TLS 1.3, client-auth
required, contracted gateway identity) with the **worst-case** body
(`durationYears = 30` → 360 DECIMAL128 iterations) and fails the pipeline on any
breach.

Reference run: fast-jar artefact, 4 000 requests after a 400-request warm-up,
concurrency 8, client on the same host (no network hop inserted). The per-identity
rate limiter is lifted for the run (`SIM_RATE_LIMIT_RPS`): the campaign drives far
more than 100 req/s, and what is measured is the service's latency, not its
limiter.

| Figure | Budget asserted | Measured |
| --- | --- | --- |
| p50 | ≤ 15 ms | **1.14 ms** |
| p95 (server-side, NFG-001) | ≤ 50 ms | **2.12 ms** |
| p99 | ≤ 120 ms | **3.05 ms** |
| max | — | 18.95 ms (first-sample JIT outlier) |
| mean | — | 1.28 ms |
| Request payload | < 1 024 B | **93 B** |
| Response payload | < 1 024 B | **128 B** |
| Failed requests | 0 | **0** |

The measured p99 of **3.05 ms** is three orders of magnitude inside the 2 500 ms
server timeout, which is what makes that timeout a saturation guard rather than a
latency expectation (W6-T05).

Reproduce:

```
mvn -f souscription-simulation-service/pom.xml -DskipTests package
.ci/templates/latency-budget.sh souscription-simulation-service /v1/simulations 15 50 120 1024
```

The published contract figures (p95 60 ms, p99 400 ms) are left untouched: they
are the **caller-visible** budget and include the in-cluster hop and the caller's
own queueing. The measured values are an order of magnitude inside them, which is
what makes the 2 500 ms server timeout and the 3 s caller timeout saturation
guards rather than latency expectations.

Payloads are bounded by construction — four scalars in, four scalars out, no
collection in either direction — so the < 1 KB ceiling cannot be breached by
input size.

## The single reusable contract-test suite

There is **one** contract-test suite, and the provider and both consumers run
**that** suite — no consumer pipeline writes its own:

```
Suite:   .ci/templates/contract-suite.sh <module_path> <artefact name> <frozen contracts...>
Command: .ci/templates/contract-suite.sh souscription-simulation-service \
           souscription-simulation-service-openapi-1.0.0.yaml \
           global/contracts/souscription-gateway-to-souscription-simulation-service/openapi.yaml \
           global/contracts/souscription-service-to-souscription-simulation-service/openapi.yaml
```

| Half of the suite | What it runs | Membership |
| --- | --- | --- |
| Provider contract tests | `mvn -Pcontract-suite test` — Surefire `<groups>contract</groups>` | every test carrying `@Tag("contract")`: `OpenApiDocumentTest`, `OpenApiServingParityTest`, `SimulationValidationIT`, `SimulationIdempotencyTest`, `FaultInjectionTest`, `MutualTlsConformanceTest`, `SimulationContractIT` (the JSON contract matrix of `POST /v1/simulations` added by plan 29 — nominal case, inclusive bounds, `problem+json` errors with the wire field names and the French messages, exactly the five contracted response fields, scale 2 on every monetary field, response media type). A test joins the suite by carrying the tag — no pipeline or script edit |
| OpenAPI diff | `.ci/templates/openapi-contract-diff.py target/openapi.yaml <frozen contracts>` | path, operationId, parameters, request/response property names, types and required lists |

| Pipeline that runs the suite | Role | Stage |
| --- | --- | --- |
| `souscription-simulation-service` | provider | `contract-suite` of `.ci/templates/quarkus-service-pipeline.yml` |
| `souscription-gateway` | consumer | same script, against the published artefact below |
| `souscription-service` | consumer | same script, against the published artefact below |

A test is added to the suite by tagging it, never by listing it in a pipeline;
that is what keeps the three pipelines on one suite.

## The provider verification report

The suite ends by publishing one readable verdict per contract axis, so the gate
is evidence rather than a green tick in a log:

```
.ci/templates/contract-verification-report.py <module_path> <application> <artefact name>
-> <module_path>/target/contract-verification-report.md
   <module_path>/target/contract-verification-report.json
```

| Axis | What it verifies | Where the verdict comes from |
| --- | --- | --- |
| roles | the two contracts this service **provides**, and that it **consumes none** — so no stub or mock is required | `global/contracts/INTER_APPLICATION_CONTRACTS.json` + `ProviderContractVerificationIT#providesTwoContractsAndConsumesNone` |
| openapi | empty diff of `target/openapi.yaml` against both frozen documents | `openapi-contract-diff.py` |
| schema | a live 200 body validates against `shared-schemas/SimulationResult.json` (including the `^[0-9]{1,6}\.[0-9]{2}$` pattern on `contributed` and `finalCapital`) and a live 400 body against `FieldErrors.json`, both on the **raw** body | `ProviderContractVerificationIT`, `SimulationContractIT` |
| resilience | server timeout 2 500 ms < 3 000 ms caller timeout, bulkhead 20/40, `503` on overload, `429` on rate-limit excess, 4xx never retryable | `ProviderContractVerificationIT`, `FaultInjectionTest` |
| security | TLS 1.3 only, `client-auth=required`, only the two contractual workload identities, no external route | `ProviderContractVerificationIT`, `MutualTlsConformanceTest` |

Both halves are read from the frozen files, never restated: the implemented
figure is compared with `resilience-contract.json` and `security-policy.json`
themselves, so a contract edit cannot pass unnoticed. A `FAIL` on any axis fails
the `contract-suite` stage, and an axis with no test that ran is a `FAIL`, not an
omission.

The report is published as a pipeline artefact next to the OpenAPI document and
is **referenced by both consumer pipelines** (`souscription-gateway`,
`souscription-service`) as the provider-side evidence accompanying the artefact
they build against. It asserts nothing about a caller's behaviour: the
no-fallback and no-local-recompute rules below are the callers' to enforce, and
their verification belongs to `souscription-service`.

## Consumers of the published contract

The build writes `target/openapi.yaml`; the blocking `contract-suite` stage runs
the suite above and publishes the versioned artefact:

```
souscription-simulation-service-openapi-1.0.0.yaml
```

That artefact is a **required input** of the two consumer pipelines, which run
their consumer-side contract tests against it rather than against the running
service:

| Consumer pipeline | Frozen contract | Consumes |
| --- | --- | --- |
| `souscription-gateway` | `global/contracts/souscription-gateway-to-souscription-simulation-service/openapi.yaml` | `souscription-simulation-service-openapi-1.0.0.yaml` |
| `souscription-service` | `global/contracts/souscription-service-to-souscription-simulation-service/openapi.yaml` | `souscription-simulation-service-openapi-1.0.0.yaml` |

The artefact name carries `info.version`, so a contract change forces a version
bump and a visible re-wiring of both consumer pipelines. The document is served
on the management port only (`/q/openapi` on 9000) and is unreachable from
outside the cluster: consumers read the artefact, never the endpoint.

A divergence is fixed **in this service**, never by editing `global/contracts/`.
