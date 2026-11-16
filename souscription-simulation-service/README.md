# souscription-simulation-service

Quarkus service hosting the premium simulation. Stateless: no datasource, no HTTP
session. Runtime configuration lives in `src/main/resources/application.properties`
and every environment-dependent value uses the MicroProfile Config form
`${VAR:default}` (e.g. `SIM_HTTP_PORT`).

## Development loop

```
mvn -f souscription-simulation-service/pom.xml quarkus:dev
```

`quarkus:dev` replaces the legacy servlet-container rebuild-and-redeploy cycle
described in the root `README.md`: sources are recompiled and reloaded on the next HTTP request
(sub-second live reload), with no WAR packaging and no container restart.

## Reference build

```
mvn -f souscription-simulation-service/pom.xml clean verify
```

## Startup time

Startup time is read from the Quarkus log line emitted at boot:

```
souscription-simulation-service 1.0.0-SNAPSHOT on JVM ... started in 0.95s.
```

Target: `started in` under 2 s (NFG-002).

Last measured by `.ci/templates/smoke-test.sh` on the fast-jar artefact:
`startup_seconds=0.533`, `resting_rss_mb=159` (within the 120–200 MB band of
NFG-004). The pipeline records both in `target/smoke-test.log`, archived with the
scan reports; the reference `clean verify` takes about 20 s, well inside the 5 min
pipeline budget.

## Thread model, capacity and scaling

The projection is **CPU-bound and purely in-process**: up to 360 `BigDecimal`
DECIMAL128 iterations for `durationYears = 30`, with no file read, no outbound
call and no catalogue lookup on the request path (the annual rate is supplied by
the caller). `SimulationResource.compute` therefore carries `@Blocking` and
returns `SimulationResponse` directly — no `Uni`, `Multi` or `CompletionStage`,
since there is no I/O to overlap. The computation runs on a Quarkus worker
thread, never on a Vert.x event loop, which is asserted by
`EventLoopSafetyTest`: a burst of 64 worst-case requests at concurrency 16 must
produce **zero** blocked-thread-checker warning.

`quarkus.vertx.max-event-loop-execute-time=2S` and
`quarkus.vertx.warning-exception-time=2S` make any future event-loop violation
observable instead of silent.

### Measured sizing

`SimulationThroughputTest` measures the per-request CPU cost of the worst case
(30 years = 360 DECIMAL128 iterations, audit log included), using
`ThreadMXBean` CPU time over 5 000 iterations after warm-up:

| Figure | Measured |
| --- | --- |
| CPU per worst-case request | ~88 µs |
| Sustained throughput | ~11 300 simulations/s/core |

Target: ≥ 500 simulations/s/core — met with a wide margin (the test fails below
that floor). Derived pool settings, all externalised with defaults:

| Property | Default | Rationale |
| --- | --- | --- |
| `quarkus.thread-pool.core-threads` | `${SIM_CORE_THREADS:8}` | one worker per core on the reference 8-core node |
| `quarkus.thread-pool.max-threads` | `${SIM_MAX_THREADS:16}` | 2× the cores: headroom when a worker is momentarily descheduled, without oversubscribing a CPU-saturated node |
| `quarkus.thread-pool.queue-size` | `${SIM_QUEUE_SIZE:64}` | ~4 queued requests per thread, so queue wait stays bounded (64 × 88 µs ≈ 6 ms at saturation) |
| `quarkus.http.limits.max-connections` | `${SIM_MAX_CONNECTIONS:200}` | connection ceiling ahead of the worker pool; excess load is rejected at the edge rather than queued unboundedly |

### Scale unit

The scale unit is **CPU-driven**: autoscaling is piloted on CPU utilisation, not
on memory, because this service *is* the CPU load. Resting RSS is flat (no
datasource, no session, no cache), so memory never signals saturation; the
worker pool and the queue do, and they saturate the cores first. Add replicas
when sustained CPU utilisation crosses the target, not when RSS grows.

### Bulkhead, server timeout and fast rejection (W4-T03)

`SimulationResource.compute` carries the MicroProfile Fault Tolerance limits that
match the frozen resilience contracts in `global/contracts/`:

| Annotation | Value | Source |
| --- | --- | --- |
| `@Bulkhead(value = 20, waitingTaskQueue = 40)` | 20 concurrent calls, bounded queue of 40 | `souscription-service-to-souscription-simulation-service/resilience-contract.json` → `bulkhead.maxConcurrentCalls = 20` (the stricter of the two contracts; the gateway contract states 30) |
| `@Timeout(2500 ms)` | server-side budget | strictly below the 3 000 ms caller timeout of both contracts, so saturation yields a clean server rejection before the caller gives up |

Beyond the bulkhead, `BulkheadException` is mapped by `BulkheadExceptionMapper`
(and `TimeoutException` by `TimeoutExceptionMapper`) to **503** with
`Content-Type: application/problem+json` and the `FieldErrors` shape (`type`,
`title`, `status: 503`, no `errors` array). Overload never returns 500, never
returns 429, and the queue is always finite — an unbounded queue would turn fast
rejection into unbounded latency. **429 is reserved for rate limiting**
(`RateLimitFilter`, 100 req/s and a burst of 200 per calling workload identity,
from `security-policy.json`) and is never emitted by the bulkhead; see
`CONTRACT.md` for the full rate-limiting and idempotency contract.

Locked by `SimulationOverloadTest`, which saturates beyond `20 + 40` concurrent
calls and asserts the excess receives 503 problem+json rather than a hung
connection or a 500. The test widens the worker pool through a Quarkus test
profile so the limit reached first is the contractual bulkhead, not the
production pool (16 threads, narrower than the bulkhead). The same profile widens
`quarkus.http.accept-backlog` to 512 and the client replays a connection-level
`IOException` once: a connection reset during the 150-call burst is an OS accept
failure, not a bulkhead rejection, and must be neither counted as one nor allowed
to fail the run.

Validation precedes capacity: an invalid body is a deterministic **400** even
while the bulkhead is saturated, as in the source. The fault-tolerance
interceptor would otherwise run before the method-validation interceptor and
request a permit first, so `mp.fault.tolerance.interceptor.priority=5000` places
it after validation. `OverloadRejectionParityTest` locks that ordering together
with the annotation values, the `2 500 ms < 3 000 ms` margin, the absence of
`errors[]` in the 503 body and the absence of any 429.

The 503 is deliberately **not** declared in the published OpenAPI document: the
`openapi-contract-diff` stage compares the declared responses of each operation
for strict equality against the frozen contracts, which declare only 200/400/500.
The 429 of the rate limiter is omitted for the same reason.

### Caller retry policy (frozen contract)

Callers of this service must:

- **never** retry a `4xx` response, including `429`;
- retry `502`, `503` and `504` **at most once**, with exponential backoff and
  full jitter (100 ms base, 1 s cap).

The single retry is safe because the projection is a pure, side-effect-free
computation.

### Container sizing and the cache decision (W4-T04, W4-T05)

`deploy/deployment.yml` sizes the container around the measured footprint:

| Setting | Value | Basis |
| --- | --- | --- |
| `resources.requests.memory` | `192Mi` | measured resting RSS 159 MB, rounded up |
| `resources.limits.memory` | `384Mi` | 2× the request; with `-XX:MaxRAMPercentage=70` the heap budget follows it (≈ 269Mi) |
| `readinessProbe.initialDelaySeconds` | `1` | **measured** startup time 0.533 s (`smoke-test.sh` on the fast-jar), rounded up to the 1 s probe granularity — not an arbitrary grace period |
| `securityContext` | `runAsNonRoot: true`, `runAsUser: 185`, `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]` | matches the image (`USER 185`) ; the only writable path is a `tmpfs` on `/tmp` |

Measured RSS under load: **159 MB** resting and flat under the throughput test —
inside the 120–200 MB band of NFG-004. The footprint contains **no datasource, no
connection pool and no cache** (`grep -rniE "datasource|hikari|jdbc|h2" src/main`
returns nothing; the only `cache` occurrences are comments explaining its absence),
so memory does not grow with traffic and autoscaling stays CPU-driven.

#### Cache decision: **not enabled**

The duplicate-input ratio is instrumented rather than assumed.
`SimulationInputDigestMeter` keeps a bounded LRU of **1 024** input digests
(constant memory, digests only — not results) and exports two untagged Micrometer
counters:

- `simulation.inputs.distinct` — input not present in the recent window;
- `simulation.inputs.repeated` — input already present in the recent window.

Neither counter carries a tag: tagging by amount, rate or correlation id would be
unbounded cardinality. `DuplicateInputRatioTest` locks the classification, the
absence of tags and the absence of any `cache_gets` series.
`SizingCacheDecisionParityTest` (parity check 19) re-asserts the same invariants from
the outside: both counters registered and untagged, the digest window bounded to its
fixed capacity under 3× overflow, and — only if a cache is ever configured — two
byte-identical responses for the same body (skipped while no cache exists).

Measured on the reference `mvn clean test` traffic:
`distinct = 35`, `repeated = 265` → **repeated ratio 0.88**. That figure is *not*
treated as evidence for a cache: it is dominated by the overload and parity tests
replaying one identical payload, so it measures the test harness, not production
usage.

A cache is therefore **not enabled**, for two reasons:

1. a worst-case computation costs ~88 µs of CPU and is pure, so the saving per hit
   is small while a cache adds a sized, evictable, invalidation-free-but-still-tunable
   component to a service whose selling point is a flat footprint;
2. no production duplicate ratio has been observed yet — the counters exist to
   produce one.

Revisit when production shows a **sustained repeated ratio above 0.5 while CPU is
the scaling constraint**. The change then is a bounded `quarkus-cache` on the
computation, with `cache_gets{result}` exported and a test proving results are
identical with and without the cache (the computation is pure, so that test is
cheap). Until then, enabling a cache would be a guess.

## Health probes and management port (W5-T01)

The three probes are served **only** on the management port, never on the traffic
port:

| Endpoint | Port | Content |
| --- | --- | --- |
| `/q/health/live` | `${SIM_MANAGEMENT_PORT:9000}` | liveness — process-level only, no check registered |
| `/q/health/ready` | `${SIM_MANAGEMENT_PORT:9000}` | readiness — `simulation-worker-pool` |
| `/q/health/started` | `${SIM_MANAGEMENT_PORT:9000}` | startup |

`quarkus.management.enabled=true` with `quarkus.smallrye-health.management.enabled=true`
moves them there; `GET /q/health/live` on port 8080 returns **404**. The Prometheus
scrape follows the management interface (`/q/metrics` on 9000 — the extension has no
per-endpoint override), which is why `prometheus.io/port` is `"9000"` in the manifest.
The OpenAPI document is served on the **management port** as well
(`quarkus.smallrye-openapi.management.enabled=true`, W5-T06): its `/q/openapi` path stays
frozen by the `openapi-contract-diff` stage, but the document is no longer reachable from
outside the cluster — consumers read the published pipeline artefact, not the endpoint.
The management port is not published by the Service and is unreachable from outside the
cluster (enforced in plan 24).

## OpenAPI document, Swagger UI and consumers (W5-T06)

| Surface | Port | Profiles |
| --- | --- | --- |
| `/q/openapi` | `${SIM_MANAGEMENT_PORT:9000}` | all |
| `/q/swagger-ui` | `${SIM_MANAGEMENT_PORT:9000}` | `dev` and `test` only |

Swagger UI is an unauthenticated surface, so `quarkus.swagger-ui.always-include` is
`false` by default and set to `true` only under `%dev` and `%test`. The key is
**build-time**: a fast-jar built outside dev/test does not even package the UI, so
`GET /q/swagger-ui` returns **404** on both ports in production — the UI cannot be
re-enabled by a runtime property.

```
$ SIM_HTTP_PORT=8080 SIM_MANAGEMENT_PORT=9000 \
    java -Dquarkus.profile=prod -jar target/quarkus-app/quarkus-run.jar
$ curl -s -o /dev/null -w "%{http_code}\n" http://localhost:9000/q/openapi
200
$ curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/q/openapi
404
$ curl -s -o /dev/null -w "%{http_code}\n" http://localhost:9000/q/swagger-ui
404
$ curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/q/swagger-ui
404
```

In dev mode the UI is served, on the management port only:

```
$ SIM_HTTP_PORT=8080 SIM_MANAGEMENT_PORT=9000 mvn quarkus:dev
$ curl -s -o /dev/null -w "%{http_code}\n" http://localhost:9000/q/swagger-ui
302          # -> /q/swagger-ui/
$ curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/q/swagger-ui
404
```

`OpenApiDocumentTest` locks the document content and its absence from the traffic port;
`SwaggerUiProfileTest` locks the dev/test activation.

### Consumers

The build always writes `target/openapi.yaml`
(`quarkus.smallrye-openapi.store-schema-directory=target`). The `openapi-contract-diff`
stage diffs it against the two frozen contracts in `global/contracts` and publishes it as
the versioned artefact:

```
souscription-simulation-service-openapi-1.0.0.yaml
```

That artefact is declared as an input of the two consumer pipelines:

| Consumer pipeline | Frozen contract |
| --- | --- |
| `souscription-gateway` | `global/contracts/souscription-gateway-to-souscription-simulation-service/openapi.yaml` |
| `souscription-service` | `global/contracts/souscription-service-to-souscription-simulation-service/openapi.yaml` |

The artefact name carries `info.version` (`1.0.0`), so a contract change forces a version
bump and a visible re-wiring of both consumer pipelines.

Verified on the fast-jar artefact:

```
$ SIM_HTTP_PORT=8080 SIM_MANAGEMENT_PORT=9000 java -jar target/quarkus-app/quarkus-run.jar
$ for p in live ready started; do curl -s -o /dev/null -w "$p %{http_code}\n" \
    http://localhost:9000/q/health/$p; done
live 200
ready 200
started 200
$ curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/q/health/live
404
```

`ready` reports a single check, `simulation-worker-pool`.

### Readiness: worker-pool saturation, and nothing else

`WorkerPoolHealthCheck` reads the Micrometer gauge `worker_pool_queue_size` and compares
it with `quarkus.thread-pool.queue-size` (default 64). Queue full ⇒ readiness **DOWN** ⇒
the replica is removed from the Service endpoints after `failureThreshold: 3` readiness
intervals (3 × `periodSeconds: 5`, so up to 15 s) and
rejoins when the queue drains. Liveness stays **UP**: a load spike must not kill the
process, or a burst would turn into a restart loop. An absent gauge is reported UP — a
missing metric is not a saturation.

The check depends on **no other application**: this service has no datasource, no
outbound call and no catalogue lookup, so readiness cannot import a third party's
outage.

```
$ grep -rn "RestClient\|HttpClient\|DataSource" src/main/java/fr/demo/souscription/simulation/health
(no match)
```

`WorkerPoolHealthCheckTest` locks both verdicts (UP below capacity, DOWN at and above
capacity) plus the absent-gauge case; `ManagementHealthEndpointTest` locks the three
endpoints on the management port and their absence from the traffic port.

### Metrics: Prometheus exposition (W5-T02)

`/q/metrics` is served on the management port (same reasoning as the probes: not published
by the Service, unreachable from outside the cluster). Three families are exposed:

| Family | Source | Used for |
| --- | --- | --- |
| `http_server_requests_seconds_*` | `quarkus.micrometer.binder.http-server.enabled=true` | availability (`outcome`/`status`), p95, p99 |
| `jvm_*` | `quarkus.micrometer.binder.jvm=true` | heap, GC, threads (NFG-004) |
| `simulation_calculate_seconds_*` | `@Timed("simulation.calculate")` on `SimulationService.calculate` | duration of the computation alone |

Keeping the business timer separate from the HTTP timer is mandatory (NFG-001): a latency
regression must be attributable to the computation rather than to the transport. The two
are read side by side — `simulation_calculate_seconds` flat while
`http_server_requests_seconds` rises means a transport or queueing problem, not an
arithmetic one.

**No high-cardinality label anywhere.** No meter is tagged by amount, rate, correlation id
or trace id; the duplicate-input counters (`simulation.inputs.distinct` /
`simulation.inputs.repeated`) carry no tag at all and keep only an integer digest.

```
$ grep -rn "Tags.of\|\.tag(" src/main/java
(no match)
```

Alert rules derived from the published SLO (99.9 % availability over 5 min, p95 < 50 ms,
p99 < 120 ms, 503 rate < 1 %, worker-pool queue saturation) live in
`deploy/alerts/simulation-alerts.yml`, with promtool unit tests in
`deploy/alerts/simulation-alerts-test.yml`. The blocking `alert-rules-test` pipeline stage
runs `promtool check rules` and `promtool test rules`, so a rule can never reference a
metric the service does not expose. `MetricsExpositionTest` and `MetricsParityTest` lock the
exposition itself.

**Histogram buckets.** `MetricsDistributionConfig` registers a Micrometer `MeterFilter` that
enables `percentilesHistogram` on `http.server.requests` and `simulation.calculate`, and declares
the 50 ms / 120 ms SLO boundaries. Without it the registry publishes only `_count`, `_sum` and
`_max`: `histogram_quantile(...)` in `simulation-alerts.yml` would have no
`http_server_requests_seconds_bucket` series to read and the published p95/p99 objectives would be
unverifiable. Server-side buckets rather than client-side percentiles because pre-computed
percentiles cannot be aggregated across the ≥ 2 replicas the 99.9 % objective requires.

`promtool` is not installed in the local build environment, so `promtool test rules` is executed
only by the `alert-rules-test` pipeline stage; the local deferral is recorded in the plan-21
`test-gaps.md` and replayed by plan 31.

### Graceful shutdown: 0 lost request

`quarkus.shutdown.timeout=20S` and `terminationGracePeriodSeconds: 30` (the grace period
must exceed the shutdown timeout, or the kubelet would `SIGKILL` a still-draining
process). On `SIGTERM` the HTTP listener stops accepting, in-flight requests complete,
then the process exits.

Measured on the fast-jar with 32 concurrent clients, traffic stopped at the instant of
`SIGTERM` (as Kubernetes removes the endpoint from the Service before the signal):

| Figure | Measured |
| --- | --- |
| Requests completed | 18 322 |
| **Lost requests** | **0** |
| Drain time | 1.06 s (`stopped in 1.037s`) |
| Exit code | 143 (SIGTERM) |

Rollout procedure, same expectation of 0 lost request:

```
# run a load generator against the Service, then:
kubectl rollout restart deployment/souscription-simulation-service
kubectl rollout status  deployment/souscription-simulation-service
```

The readiness probe gates the new pods, the old pods drain within the grace period, and
the generator must report no 5xx and no connection reset. The single caller retry
(`503`/`504`, at most once) is a second line of defence, not the mechanism.

The cluster form of this evidence (`kubectl rollout` with a concurrent load generator,
0 lost request) is **deferred — no cluster in this environment**. Owner: the
souscription-simulation-service platform owner (service DRI, see
`VALIDATION_TRACEABILITY.md`); replayed by `31-simulation-end-to-end-validation-audit`.

### Horizontal scaling at 2 and 4 replicas

The service is stateless — no session, no datasource, no in-memory cache — so any replica
can serve any request and nothing prevents linear scaling. Measured on the fast-jar,
replicas as separate JVMs on the reference 15-core host, ApacheBench with keep-alive,
12 s steady state, total offered load fixed at 16 concurrent connections:

| Replicas | Connections per replica | Total throughput | p95 | Failed requests |
| --- | --- | --- | --- | --- |
| 2 | 8 | ~31 600 req/s | 1 ms | 0 |
| 4 | 4 | ~27 100 req/s | 1 ms | 0 |

Read this honestly: **total throughput is flat because all replicas share one host's
CPU**, and this service is pure CPU. What the campaign does establish is that replica
count is free of affinity — load spreads over 2 or 4 replicas with 0 failed request and
unchanged latency. Capacity per replica is the single-core figure measured by
`SimulationThroughputTest` (~11 300 simulations/s/core), so added capacity comes from
added **cores**: one replica per node scales linearly, four replicas on one node do not.
Re-run the campaign on the cluster, one replica per node, to record the cluster figure.
That cluster run is **deferred — no multi-node cluster in this environment**. Owner: the
souscription-simulation-service platform owner (service DRI); a sub-linear cluster result
is a finding, not an acceptable outcome (re-check `StatelessnessParityTest`).

Reproduce (single host, per replica `i` on ports `18080+i` / `19000+i`):

```
ab -k -c 8 -t 12 -n 2000000 -p body.json -T application/json \
   http://localhost:18080/v1/simulations
```

## Tracing and correlated JSON logs (W5-T03, W5-T04)

OpenTelemetry is enabled and exports over OTLP/gRPC. The collector endpoint is **never
hardcoded**: `quarkus.otel.exporter.otlp.endpoint` reads `OTEL_EXPORTER_OTLP_ENDPOINT`
(the `http://localhost:4317` default only serves the development loop). The service name
reported to the collector is `souscription-simulation-service`.

Sampling is error-biased, in two stages, because the outcome of a request is unknown when
the sampling decision is taken:

| Stage | Class | Behaviour |
| --- | --- | --- |
| Start | `ErrorBiasedSampler` | parent-based trace-id ratio, ratio from `OTEL_TRACES_SAMPLE_RATIO` (default 0.1). What the ratio would drop is downgraded to **record-only** instead of dropped. |
| End | `ErrorBiasedSpanProcessor` | A record-only span whose status is `ERROR` is exported to every configured exporter. Spans already sampled are left to the regular pipeline (no double export). |

Net effect: **100% of failed requests are traced** whatever the ratio, successes follow
`OTEL_TRACES_SAMPLE_RATIO`. Locked by `ErrorBiasedSamplingTest`.

Every error response marks the current span `ERROR` (`ProblemJson.response`, the single exit
of all the problem+json mappers): the HTTP layer leaves a 4xx server span `UNSET`, so without
that marking the error bias would have nothing to select and a 400 would be invisible in the
collector at a 0.1 success ratio. The response body and status are unchanged — the span status
is the only side effect. Locked by `TracePropagationParityTest.allErrorResponsesAreTraced`.

`TracePropagationParityTest` runs under a class-scoped test profile forcing
`quarkus.otel.traces.sampler.arg=1` (the `%test` value stays 0 so `TracePropagationTest` can
still separate the ratio from the error bias in a single instance).

Propagation (`CorrelationIdFilter`): an incoming W3C `traceparent` is continued — the
server span reuses the caller's trace id and takes the caller's span as parent, no new
trace is created (`TracePropagationTest`, `TracePropagationParityTest`). `X-Correlation-Id` is copied as received into
the span attribute `correlation.id` and into the MDC, echoed back on the response, and
never re-minted when the header is present; absent the header, the trace id is used as the
correlation value. The value is capped at 64 characters and stripped of anything outside
`[A-Za-z0-9._:-]`, because it reaches the logs and a newline-carrying header would allow
log injection.

Logs are 100% JSON (`quarkus.log.console.json=true`, no `slf4j-simple` on the classpath)
and every request line carries `traceId`, `spanId` and `correlationId` in the `mdc` object,
so a log line and a span are joinable on `traceId`. Parse failure rate 0 and the presence
of `traceId` / `spanId` are asserted by `AuditLogParityTest`.

Correlation query (Loki/Elasticsearch-style, one trace across the services):

```
{app=~"souscription-.*"} | json | traceId = "<traceId>"
```

### 3-level trace of the confirmation path — integration-level, not verified here

A confirmation must produce a single trace spanning
`souscription-gateway → souscription-service → souscription-simulation-service`. This
module can only prove its own leg (`TracePropagationTest`: the incoming `traceparent` is
continued, the server span is complete). The end-to-end check requires the two sibling
applications deployed against the same collector and is **deferred to the integration
environment**; it is not reproducible from this repository. Procedure, once deployed:

1. `POST /api/v1/souscriptions/{id}/confirmation` on the gateway with
   `X-Correlation-Id: <uuid>` and no `traceparent`.
2. Read the `traceId` from the gateway log line matching that correlation id:
   `{app="souscription-gateway"} | json | correlationId = "<uuid>"`.
3. Fetch the trace by that id and assert three spans, one per service, in one trace:
   `traceID = "<traceId>"` (Tempo/Jaeger), expected spans
   `POST /api/v1/souscriptions/{id}/confirmation` (gateway, SERVER) →
   `POST /v1/souscriptions/{id}/confirmation` (souscription-service, SERVER + CLIENT) →
   `POST /v1/simulations` (souscription-simulation-service, SERVER).
4. Assert the same `correlationId` on the log lines of all three services for that
   `traceId`.

Owner: the souscription-simulation-service platform owner (service DRI), jointly with the
gateway and subscription service owners. A missing third level means the remote simulation
call is not instrumented and must be treated as a finding, not an accepted gap.

## Transport security and network isolation (W6-T01, W6-T02)

The projection used to be an in-process method call inside the monolith: unreachable
by construction, and authenticated by nothing because there was nothing to
authenticate. Now that it crosses the network, two independent layers replace that
property — neither is sufficient alone:

1. **Mutual TLS on the only traffic port.** `quarkus.http.ssl-port=8443` with
   `quarkus.http.insecure-requests=disabled`: there is no plaintext listener to fall
   back to, so a misconfigured caller fails rather than downgrading. `client-auth` is
   `required`, `protocols` is `TLSv1.3` only and the cipher suites are the two frozen
   in the contracts (`TLS_AES_256_GCM_SHA384`, `TLS_AES_128_GCM_SHA256`). A caller
   with no client certificate is rejected **in the handshake**, before any HTTP
   request exists — never with an application 401/403.
2. **A workload-identity allow-list.** The cluster PKI issues a certificate to every
   pod, so trusting the CA would admit the whole cluster.
   `WorkloadIdentityFilter` additionally requires the peer certificate to carry one of
   the SPIFFE identities frozen in the two security policies — `souscription-gateway`
   or `souscription-service` — read from the `subjectAltName` URI entries, never from
   a header. Every other PKI-valid identity gets `403 application/problem+json`, and
   the body does not disclose the accepted identities. The list itself is
   configuration (`simulation.mtls.accepted-identities`), so a contract change is a
   redeploy, not a code change.
3. **Default-deny networking.** `deploy/networkpolicy.yml` admits ingress on 8443 from
   the two contracted workloads only (souscription-web is absent: the SPA goes through
   the gateway), admits the kubelet on the unpublished management port, and limits
   egress to the OTLP collector, the metrics scraper and DNS. There is **no Ingress and
   no Route object** in `deploy/`, and the `Service` is `ClusterIP` with a single port:
   that absence is what makes `publiclyReachable: false` true.

Certificate, key and CA paths come only from the environment (`TLS_CERT_PATH`,
`TLS_KEY_PATH`, `TLS_CA_PATH`), mounted read-only from the cluster-PKI secret. No
certificate, key or passphrase exists in this repository or in the image:

```
grep -rniE "BEGIN (RSA |EC )?PRIV[A]TE KEY|BEGIN CERTIFI[C]ATE|pass[w]ord=" souscription-simulation-service/
```

returns nothing, including after a build — the throwaway development certificates are
generated by `.ci/generate-dev-certs.sh` into `../target/dev-certs`, i.e. the reactor
build directory, outside this module.

Nothing of the edge is duplicated here. CSRF (double-submit cookie), the HTTP-method
allow-list and the five browser security response headers of the legacy
`web/SecurityFilter.java` stay at `souscription-gateway`
(`grep -rni "csrf" src/main` returns nothing); a second implementation would diverge
from the gateway's on the first change. No CORS configuration is introduced either:
this call is cluster-internal and has no browser origin. There is no end-user
authentication anywhere — none exists in the source and none is introduced; mTLS
authenticates the calling *workload* only.

### Tests behind the real transport

`@QuarkusTest` suites talk to the same TLS 1.3, client-auth-required transport as
production, with no plaintext exception: `MutualTlsHarness` is registered as a
`QuarkusTestBeforeEachCallback` and gives every suite the contracted gateway
identity. It is a Quarkus callback and not a JUnit extension on purpose — test
classes run in the Quarkus deployment classloader, so a JUnit-level extension would
configure a different copy of `RestAssured` and leave the handshake silently
unauthenticated. `MutualTlsConformanceTest` asserts the three verdicts at the layer
that enforces them: handshake failure without a certificate, 403 for the PKI-valid
but uncontracted identity, 200 for a contracted one. The management port stays
plaintext: it is unpublished, and the kubelet holds no workload certificate.

The pipeline repeats this against a deployed replica in the blocking
`mtls-conformance` stage (see *Pipeline* below).

## Quarkus version policy

The platform is pinned by `quarkus.platform.version` in **one** place for the four
Quarkus services: `global/platform/pom.xml`, the `<parent>` of this POM (W6-T06,
NFG-019). This module references the property and must never redefine it.
The pin is currently `3.20.6.2`, the
latest patch of the 3.20 LTS stream. Apply LTS patch releases (3.20.x) as they
are published. Never downgrade `quarkus.platform.version` below `3.20.5`
(NFG-018): `3.20.0` carries CVE-2025-66560 (MEDIUM) and `3.20.5` is clean of it,
but `3.20.5` itself carries CVE-2026-50559 and CVE-2026-39852 (HIGH,
authentication/authorization bypass in `quarkus-vertx-http`), fixed in
`3.20.6.2`. The blocking dependency scan in the pipeline enforces this on every
build.

### Security pins on top of the platform BOM

The platform BOM alone leaves HIGH/CRITICAL findings in transitive libraries, so
the following BOMs are imported *before* `quarkus-bom` (earliest import wins).
Each is the lowest clean patch of the line the platform already ships - upgrades
only, never downgrades. Drop a pin once the platform ships that version or newer:

| Pin | Version | Reason |
| --- | --- | --- |
| `io.netty:netty-bom` | 4.1.137.Final | CVE-2026-75595 (CRITICAL) + HIGH findings in `netty-handler`, `netty-codec*` |
| `com.fasterxml.jackson:jackson-bom` | 2.18.11 | HIGH findings in `jackson-core` / `jackson-databind` |
| `io.micrometer:micrometer-bom` | 1.15.12 | CVE-2026-40984 (HIGH), unfixed in the 1.14.x line |
| `io.vertx:vertx-dependencies` | 4.5.34 | CVE-2026-6860, CVE-2026-1002, and CVE-2026-15075 / CVE-2026-15076 (HIGH, 8.2) which affect 4.x up to and including 4.5.29 |
| `com.google.guava:guava` | 33.7.2-jre | CVE-2026-102554 (HIGH) |
| `org.yaml:snakeyaml` | 2.3 | the Vert.x BOM manages 2.0; pin prevents that downgrade |

With these pins the resolved runtime tree reports **0 CVE of severity HIGH or
above**.

## Container image

`src/main/docker/Dockerfile.jvm` packages the default `fast-jar` output
(`target/quarkus-app/`); the target decision is `jvm-only`, so there is no native
image. The image:

- pins the base image **by digest** (`registry.access.redhat.com/ubi9/openjdk-21-runtime@sha256:...`),
  never by a floating tag;
- runs as the non-root user `185`;
- sets `-XX:MaxRAMPercentage=70` so the heap follows the container memory limit;
- expects a **read-only root filesystem**, enforced by the deployment manifest
  (mount a writable `tmpfs` on `/tmp` only).

```
mvn -f souscription-simulation-service/pom.xml clean package -DskipTests
docker build -f souscription-simulation-service/src/main/docker/Dockerfile.jvm \
  -t souscription/souscription-simulation-service:local souscription-simulation-service
```

Nothing from the legacy servlet-container provisioning chain is transposed: no
container download, no `server.xml`, no `context.xml`. The root POM `integration` profile is left intact
as the monolith's rollback path.

## Pipeline

`.ci/pipeline.yml` is an instantiation of the shared template
`../.ci/templates/quarkus-service-pipeline.yml` used by the four Quarkus services
(NFG-027) — parameters only, no bespoke stage. Stages, in order:

1. `mvn -f souscription-simulation-service/pom.xml clean verify`
2. the single reusable contract suite (`.ci/templates/contract-suite.sh`) —
   **blocking**: the tests tagged `contract` plus the OpenAPI diff against both
   frozen contracts; publishes
   `souscription-simulation-service-openapi-1.0.0.yaml`, which the two consumer
   pipelines consume and whose suite they reuse (`CONTRACT.md`)
3. manifest rendering (`.ci/templates/render-manifests.py`) — **blocking**:
   `deploy/*.yml` are parameter instances and are rendered into `target/deploy/`
4. Gherkin suite (`mvn -Pcucumber`) — **blocking** (plan 30): the 10 simulation
   scenarios gate the pipeline; the template tolerates no failure on this stage
5. dependency CVE scan (`-Psecurity-scan`, OWASP Dependency-Check) — **blocking**,
   fails on CVSS ≥ 7.0 (HIGH and above), report archived (NFG-006)
6. alert-rules test (`promtool check rules` + `promtool test rules` on
   `deploy/alerts/`) — **blocking**: every SLO alert must reference a metric this
   service actually exposes on `/q/metrics`
7. mTLS and network-isolation conformance — **blocking**: the **rendered**
   `target/deploy/networkpolicy.yml`
   must be default-deny with no Ingress and no external route; a call from an
   unauthorised pod must fail to connect; a call without a client certificate must
   fail in the TLS handshake; a call from `souscription-gateway` with its workload
   certificate must succeed; `testssl.sh` confirms TLS 1.3 only (W6-T01, W6-T02)
8. container image build from `src/main/docker/Dockerfile.jvm`
9. image scan (Trivy) — **blocking** on HIGH/CRITICAL
10. publish image and archive reports
followed by a smoke test that starts the artefact, asserts the `started in Xs`
line is under 2 s (NFG-002) and records resting RSS (120–200 MB expected,
NFG-004), then by the **blocking** `latency-budget` stage that drives
`POST /v1/simulations` under load on the production transport and asserts the
budget published in `CONTRACT.md` (p50 ≤ 15 ms, p95 ≤ 50 ms per NFG-001,
p99 ≤ 120 ms, < 1 KB per direction). End-to-end pipeline budget: under 5 min
(NFG-005).

To run the dependency gate locally:

```
mvn -f souscription-simulation-service/pom.xml -Psecurity-scan verify -DskipTests
```

To run the latency-budget gate locally:

```
mvn -f souscription-simulation-service/pom.xml -DskipTests package
.ci/templates/latency-budget.sh souscription-simulation-service /v1/simulations 15 50 120 1024
```

### Operational surface: 0 bespoke infrastructure file (W6-T06, NFG-019)

Everything structural — pipeline stages, deployment, service, probes,
NetworkPolicy, the Quarkus pin, the contract suite — lives in a shared location
and is instantiated here with parameters. Counted by file:

| Infrastructure file owned by this service | Kind | Count |
| --- | --- | --- |
| Bespoke pipeline files (own stage logic) | — | **0** |
| Bespoke deployment manifests (own `apiVersion`/`kind` body) | — | **0** |
| `.ci/pipeline.yml` | parameter instance of `.ci/templates/quarkus-service-pipeline.yml` | 1 |
| `deploy/deployment.yml` | parameter instance of `.ci/templates/deploy/quarkus-service-deployment.yml` | 1 |
| `deploy/networkpolicy.yml` | parameter instance of `.ci/templates/deploy/quarkus-service-network.yml` | 1 |
| `deploy/alerts/simulation-alerts.yml` + its promtool test | service-owned **data** (rules naming this service's own metrics), not infrastructure | 2 |

Rendered manifests are build artefacts under `target/deploy/`, produced by the
blocking `render-manifests` stage and consumed by `kubectl apply` and by the
`mtls-conformance` stage. An unresolved or unknown parameter fails that stage
rather than shipping a manifest containing a literal `${...}`:

```
python3 .ci/templates/render-manifests.py souscription-simulation-service
```

| Single shared definition | Location |
| --- | --- |
| Quarkus platform BOM pin | `global/platform/pom.xml` (`quarkus.platform.version`), the `<parent>` of this POM |
| Contract-test suite | `.ci/templates/contract-suite.sh`, run by this pipeline and by both consumer pipelines (`CONTRACT.md`) |

## Gherkin suite (plan 30)

`mvn -f souscription-simulation-service/pom.xml clean verify -Pcucumber` is the
blocking Gherkin gate. `RunCucumberTest` selects the single shared feature tree at
the repository root (`../features/simulation`) and filters on `@simulation`: the 10
scenarios of this domain, 42 steps, no undefined step. No `.feature` file and no
root step-definition file is ever modified — the titles (written without
diacritics) and the `Examples` tables are the behavioural contract. Only the module
copies under `src/test/java/features/step_definitions/` carry bodies.

**Level at which the steps are driven.** The step bodies invoke
`SimulationService.calculate` and `SimulationInputValidator` **in process**, not over
`POST /v1/simulations`. This is not a choice of convenience: the Cucumber JUnit
Platform engine is not the Jupiter engine, so `@QuarkusTest` on a glue class is
inert (no application boots, no CDI injection, `RestAssured.port` stays `-1`), and
the only supported bridge (`io.quarkiverse.cucumber`) is a new dependency that this
module does not take. Both target classes are plain beans with no mandatory CDI
dependency — `SimulationService`'s clock is initialised to `Europe/Paris` by its
field initialiser — so the in-process call exercises the same code the resource
delegates to.

The HTTP projection of these same scenarios is therefore covered by REST Assured
`@QuarkusTest` contract tests, which run in the default gate and in the
`contract-suite` stage. Scenario-by-scenario compensating tests:

| Scenario | Compensating REST Assured test |
| --- | --- |
| `Simulation d'un placement standard` | `SimulationContractIT` (200, scale 2 on the four monetary fields), `SimulationResourceSmokeTest` |
| `Validation reussie des montants de simulation` | `SimulationValidationIT` (200 rows of the bounds matrix) |
| `Bornes du versement initial` | `SimulationValidationIT.initialAmountBounds` (`errors[0].field = initialAmount`) |
| `Bornes de la mensualite` | `SimulationValidationIT.monthlyAmountBounds` (`monthlyAmount`) |
| `Bornes de la duree en annees` | `SimulationValidationIT.durationYearsBounds` (`durationYears`) |
| `Format de montant refuse` | `SimulationRequestConstraintTest`, `DecimalWireParityTest` (scale > 2 and non-numeric bodies rejected 400 `problem+json`) |
| `Normalisation des montants saisis` | `DecimalContractTest`, `DecimalWireParityTest` |
| `Parametres de simulation rejetes par le moteur de calcul` | `IllegalArgumentExceptionMapperTest`, `SimulationValidationIT` (400 `problem+json`, message `Paramètres de simulation invalides`) |
| `Simulation avec une hypothese de progression nulle` | `SimulationValidationIT.annualRateBounds` (rate `0`), `SimulationServiceTest` |
| `Simulation sans mensualite` | `SimulationValidationIT.monthlyAmountBounds` (`0.00` → 200) |

The scenario labels `initial`, `monthly` and `years` are the keys of
`SimulationInputValidator.simulationErrors`; the step definition
(`SimulationSteps.wireField`) translates them into their wire counterparts
`initialAmount`, `monthlyAmount` and `durationYears` before asserting, and those
same property names are asserted on `errors[].field` by `SimulationValidationIT`.
The module copy of `CommonSteps` carries only the common steps that
`features/simulation/simulation.feature` actually uses; the common steps of the
other domains stay in the shared root stubs, owned by the sibling applications.

## Provider contract (W6-T03)

`CONTRACT.md` is the consumer-facing publication of this service: the SLA,
the server timeout and bulkhead, the caller retry rules, the transport and
accepted workload identities, the measured latency budget, the
no-client-fallback / no-local-recompute rule (the server alone determines the
sold capital) and the compounded availability of the confirmation path. Every
figure in it is cross-checked against the two `resilience-contract.json` files in
`global/contracts/`, which win on any divergence.

## Configuration explicitly NOT in this service

Each legacy descriptor concern dropped from this module is transferred to a named
owner — it is a relocation, not a relaxation:

| Dropped concern (legacy source) | Owner |
| --- | --- |
| `SecurityFilter` security response headers (`web.xml`) | `souscription-gateway` |
| CSRF protection (double-submit cookie) | `souscription-gateway` |
| HTTP method allow-list | `souscription-gateway` |
| `*.do` servlet mapping and Struts forwards (`web.xml`, `struts-config.xml`) | `souscription-gateway` (URL translation to `POST /v1/simulations`) |
| 20-minute `session-timeout` → subscription draft TTL (`web.xml`) | `souscription-service` (persisted draft table) |
| H2 datasource / JPA persistence (`context.xml`) | `souscription-service` |
| `ServletContextListener` service-locator (`web/Application.java`, `Application.get...`) | the container: ArC (CDI) owns the bean lifecycle here — `SimulationService` and `SimulationInputValidator` are `@ApplicationScoped`, resolved by injection instead of the `ServletContext` lookup; the H2 schema initialisation part of `contextInitialized` is owned by `souscription-service` |
| Client-side navigation and view rendering | `souscription-web` |

Legacy HTTP connector attributes from the monolith's container descriptor are not
transposed
beyond `quarkus.shutdown.timeout` (graceful drain) and
`quarkus.http.limits.max-body-size`; UTF-8 is covered by
`project.build.sourceEncoding` and Quarkus defaults.
