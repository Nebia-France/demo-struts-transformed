# Benchmark against the server-rendered baseline (W9-T02)

This compares the SPA with the source baseline recorded in
`apps/souscription-web/NONFUNCTIONAL_GAPS.json` -> `performanceBenchmarks`. Every value below was
measured on the commit that adds this file. Deviations and their justifications are listed in
[Deviations](#deviations).

**Environment:** Apple M5 Pro (15 cores, 24 GB), macOS 27, Node 24.18.0 / npm 11.16.0,
Chromium 153 (Lighthouse 12.1.0, Playwright), Docker 29.5.2 on a colima VM (2 vCPU, 4 GB, `vz`).
**Device profile:** the mid-tier profile of `lighthouserc.json` and `docs/perf-profile.md`
(mobile 412x823, CPU slowdown 4x, Slow 4G: 150 ms RTT, 1.6 Mbps).

## Comparison table

| Indicator | Source baseline | Target to verify | Measured |
| --- | --- | --- | --- |
| FCP / LCP | one HTML round trip, 0 kB JS | LCP <= 2,5 s on Slow 4G, initial JS <= 200 kB gzip | `/catalogue`: FCP 1 738–1 747 ms, **LCP 2 109–2 119 ms**; `/souscription/recapitulatif`: **LCP 2 106–2 127 ms** (3 runs each, `npm run perf:lh` exit 0). **Initial JS 91,95 kB gzip** over 14 chunks (`check-initial-budget`). TBT 0 ms, performance score 0,98 |
| Startup | Tomcat 10: 3–8 s + context init | static container < 1 s; SPA bootstrap 0,3–1,0 s | Container readiness (`docker run` to first `200` on `/healthz`): **104–150 ms** over 5 starts. SPA bootstrap (FCP minus end of the last initial script download, gateway reads included): **p50 228 ms, p95 255 ms** (20 runs) |
| Memory | JVM + H2: 256–512 MB RSS | static container 10–30 MB RSS; client JS heap < 50 MB | `docker stats`: **3,18 MiB idle, 3,62 MiB at 8 800 req/s**; summed `VmRSS` of the 3 nginx processes **16,3 MB** (6 380 + 4 952 + 4 952 kB). Client JS heap after GC on `recapitulatif`: **4,6 MB** (max of 20 runs) |
| Build | Maven WAR build < 1 min | `npm ci` + production build, warm cache < 2 min | Warm npm cache, `npm ci && npm run build`: **40 s** (33 s + 7 s) and **82 s** (75 s + 7 s) on two runs; worst case 82 s. `ng build` alone: 3,2 s bundle generation |
| Step navigation | 3 local server hops | p95 click-to-paint <= 500 ms, 0 duplicate confirmation | p95 over 20 journeys: `souscripteur` **188 ms**, `simulation` **178 ms**, `recapitulatif` **179 ms**, `confirmation` **207 ms** (max 275 ms). Confirmation POSTs per journey after a double click: **1** (min 1, max 1) |
| Long tasks | not applicable | no task > 200 ms | Longest `longtask` over 20 journeys: **111 ms**; longest task in the 6 Lighthouse runs: **59 ms** |

## How each value was obtained

### FCP / LCP and initial JS (step 2)

`npm run perf:lh` (`lighthouserc.json`, 3 runs per URL, simulated throttling) on the production
build served by `scripts/perf-server.mjs`. Assertion `largest-contentful-paint <= 2500 ms` passed
on both URLs (`.lighthouseci/assertion-results.json` is `[]`, exit code 0).

| URL | Run 1 | Run 2 | Run 3 |
| --- | --- | --- | --- |
| `/catalogue` LCP | 2 109 ms | 2 117 ms | 2 119 ms |
| `/souscription/recapitulatif` LCP | 2 106 ms | 2 120 ms | 2 127 ms |

The initial JS is the static import graph of the `index.html` entry, summed over the `.gz`
variants that nginx serves (`scripts/check-initial-budget.mjs`, run by `npm run build`):
`initial JS 91.95 kB gzip over 14 chunk(s) (budget 200 kB)`. The budget was not raised.

### Step navigation, JS heap, long tasks, bootstrap (step 3)

`npm run perf:steps` (`e2e/perf/step-navigation.spec.ts`) automates the DevTools procedure of
`docs/perf-profile.md`: 20 fresh browser contexts, CPU 4x and Slow 4G set through the Chrome
DevTools Protocol, then the reference journey product > `souscripteur` > `simulation` >
`recapitulatif` > double click on confirm. It reads the `step:<name>` User Timing measures, a
`longtask` PerformanceObserver, `Runtime.getHeapUsage` after `HeapProfiler.collectGarbage` on
`recapitulatif`, and counts the confirmation POSTs. Raw output: `e2e/reports/perf-steps.json`.

```json
{
  "runs": 20,
  "profile": { "cpuSlowdown": 4, "network": "Slow 4G (150 ms RTT, 1.6 Mbps)", "gatewayRttMs": 150 },
  "steps": {
    "souscripteur": { "samples": 20, "p50": 184.9, "p95": 188.1, "max": 188.2 },
    "simulation": { "samples": 20, "p50": 174.7, "p95": 177.7, "max": 178.4 },
    "recapitulatif": { "samples": 20, "p50": 175.2, "p95": 178.8, "max": 179.9 },
    "confirmation": { "samples": 20, "p50": 196.9, "p95": 207.2, "max": 275.1 }
  },
  "bootstrapMs": { "p50": 227.5, "p95": 255 },
  "longestTaskMs": 111,
  "heapMb": { "max": 4.6, "p50": 4.6 },
  "confirmationsPerRun": { "min": 1, "max": 1 }
}
```

### Static container RSS and readiness (step 4)

The image is the runtime stage of `Dockerfile` (`nginxinc/nginx-unprivileged:stable-alpine`,
`deploy/nginx.conf`, the `dist/souscription-web/browser` output of `npm run build`), 91,4 MB.
Readiness is the time from `docker run -d` until `curl -sf /healthz` succeeds:
150, 127, 107, 104, 113 ms. `deploy/smoke-headers.sh` passed against it (35 PASS, 0 FAIL).

`docker stats --no-stream`, idle:

```
CONTAINER ID   NAME       CPU %     MEM USAGE / LIMIT     MEM %     NET I/O        BLOCK I/O   PIDS
02820950067e   sw-bench   0.00%     3.176MiB / 3.814GiB   0.08%     1.27kB / 1kB   0B / 0B     3
```

`docker stats --no-stream`, during the 50-connection load test below:

```
CONTAINER ID   NAME       CPU %     MEM USAGE / LIMIT     MEM %     NET I/O          BLOCK I/O    PIDS
02820950067e   sw-bench   46.04%    3.621MiB / 3.814GiB   0.09%     37.4MB / 309MB   0B / 4.1kB   3
```

`VmRSS` of the nginx processes (`/proc/<pid>/status`): 6 380 kB (master), 4 952 kB, 4 952 kB
(workers).

### Build duration (step 5)

Measured locally with `date` around each command, npm cache warm, `node_modules` reinstalled by
`npm ci` each time: run 1 `npm ci` 33 s + `npm run build` 7 s = 40 s; run 2 75 s + 7 s = 82 s. The
spread comes from `npm ci` fetching tarballs from the npm mirror; the Angular build itself is
stable at 7 s (prebuild, `ng build`, precompression and budget gate).

### Load test (step 6)

`npm run perf:load` (`scripts/load-test.mjs`): closed loop, 50 concurrent connections, 30 s,
failing on any error or a p99 at or above the 10 s client timeout of `timeoutInterceptor`.

| Target | Paths | Requests | req/s | Errors | p50 | p95 | p99 | max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Static container (SPA origin, `souscription-web:bench`) | `/catalogue`, `/main-*.js`, `/healthz`, `/souscription/recapitulatif` | 263 987 | 8 800 | 0 | 5,2 ms | 10,4 ms | 14,4 ms | 46,7 ms |
| `souscription-gateway` (`/api/v1/csrf-token`, `/api/v1/products`) | — | not run | — | — | — | — | not measured | — |

The gateway row is not measured; see deviation 1.

## Parity re-verification (plan 42 parity check)

Re-run on the same environment, with no source changes since the table above. No threshold was
edited.

- Gate `npm ci && npm run lint && npm run build && npm test`: pass, 239/239 tests, 116 s in total
  with a warm npm cache. Initial JS is still **91,95 kB gzip** over 14 chunks.
- `npm run perf:lh`: exit 0, `assertion-results.json` is `[]`. `/catalogue` LCP 2 110 / 2 117 /
  2 127 ms (FCP 1 739–1 749 ms). `/souscription/recapitulatif` LCP 2 151 / **2 302** / 2 112 ms.
  The 2 302 ms run is above the earlier 2 127 ms maximum but still under the 2 500 ms budget. It is
  run-to-run variance on a cold deep link (deviation 8), not a regression.
- Container (`souscription-web:local`): ready on `/healthz` **401 ms** after `docker run`, timed
  from the CLI call so it includes container creation (5 s budget). `docker stats --no-stream`:

  ```
  CONTAINER ID   NAME       CPU %     MEM USAGE / LIMIT     MEM %     NET I/O           BLOCK I/O   PIDS
  e6cc52c7632e   sw-bench   0.00%     3.184MiB / 3.814GiB   0.08%     1.34kB / 1.06kB   0B / 0B     3
  ```
- `npm run perf:steps` (20 journeys): p95 `souscripteur` 188,9 ms, `simulation` 178,3 ms,
  `recapitulatif` 178,2 ms, `confirmation` 199,3 ms (budget 500 ms). Longest long task 57 ms
  (budget 200 ms). Heap 4,6 MB. 1 confirmation POST per journey.
- Gateway p99: still not measured (deviation 1, blocked).

## Deviations

1. **Gateway p95/p99 not measured (blocked).** The gateway and the three backend services are not
   deployed in any environment reachable from this repository. This is the same blocker as plans
   40 and 41. The harness is ready. Run it on the shared ingress once the platform team has
   deployed the chain:
   `TARGET=https://<spa-origin> LABEL=gateway npm run perf:load`. Then put the p95/p99 in the
   table above. Until that run, the only evidence is the static origin p99 (14,4 ms), which is
   far below the 10 s client timeout.
2. **Step navigation uses a gateway double.** `page.route` answers `/api/v1` and bypasses network
   emulation, so each answer is delayed by `GATEWAY_RTT_MS` (150 ms, the Slow 4G RTT). Real gateway
   latency on top of that RTT is not included. With a margin of at least 293 ms under the 500 ms
   budget, the gateway may add up to that much server time before a step leaves its budget. Run
   `GATEWAY_RTT_MS=<measured gateway p95 + RTT> npm run perf:steps` again once deviation 1 is
   resolved.
3. **Step budget initially exceeded, then fixed (no budget change, no debt).** The first run gave
   a p95 of 927 ms (`souscripteur`), 529 ms (`simulation`), 561 ms (`recapitulatif`) and 375 ms
   (`confirmation`). Each lazy step waited for a waterfall of chunk downloads on top of its gateway
   call. Turning on `PreloadAllModules` fixed the steps (p95 <= 201 ms), but the preload then
   competed with the `/catalogue` LCP (2 730 ms, over budget). The fix was
   `src/app/core/preload-after-landing.strategy.ts`. After the landing navigation it preloads
   only once the route has painted and the main thread is idle. After any later navigation it
   preloads at once. Result: LCP is back to 2,11 s and every step p95 is <= 207 ms. The initial JS
   is unchanged (91,95 kB gzip) because preloaded chunks are fetched after the first paint.
4. **Brotli removed from the container.** The `Dockerfile` installed `nginx-module-brotli`, which
   does not exist for this image. The image configures only the Alpine repositories, and nginx.org
   publishes no brotli module for its 1.30 Alpine packages. So the image could not be built
   (`apk add ... (no such package)`). The module install and `brotli_static on` were removed. The
   `.br` precompression was dropped, and `scripts/perf-server.mjs` now negotiates `.gz` only, so
   Lighthouse measures what nginx serves. The LCP values above are measured with gzip.
5. **Container built from the runtime stage only.** Inside the Docker VM, the build stage
   (`npm ci`) cannot reach the private npm mirror that `package-lock.json` resolves to. So the
   image was built from the runtime stage of `Dockerfile` (no changes) with the `dist/` produced on
   the host by the same `npm run build`. The served artifact is the same. The CI `docker build`
   step builds both stages.
6. **Static container memory below the 10 MB lower bound.** The cgroup memory reported by
   `docker stats` is 3,2–3,6 MiB. The summed `VmRSS` is 16,3 MB, which counts shared pages several
   times. The 10–30 MB range in `NONFUNCTIONAL_GAPS.json` is an order-of-magnitude estimate, and
   being below it is a favourable result. The value that matters is the upper bound: at least
   13,7 MB of headroom and no growth under load (+0,45 MiB at 8 800 req/s).
7. **SPA bootstrap below the 0,3 s lower bound.** 228 ms p50 is faster than the estimate. The
   initial bundle is 92 kB gzip and the landing route makes two gateway reads. This is a
   favourable result.
8. **`/souscription/recapitulatif` measured on a cold load.** Lighthouse opens the URL with no
   draft. `requireSimulation` redirects it to `/catalogue?notice=parcours`, the legacy
   `catalogue.do?notice=parcours` behaviour. So the LCP of that row is the cold deep-link
   experience. The paint of the récapitulatif inside a journey is the `recapitulatif` step
   measure: p95 179 ms.
9. **Build duration measured locally, not from pipeline step timing.** No CI run of this commit was
   available. Both local runs are under 2 minutes. Once the workflow has run, record the
   `npm ci` + `npm run build` step durations of `main-gate` here.
10. **Raw initial bundle 250 bytes over the `angular.json` warning budget (tracked debt).**
    `npm run build` prints `WARNING bundle initial exceeded maximum budget. Budget 292.00 kB was
    not met by 250 bytes with a total of 292.25 kB`. The cause is
    `withPreloading(PreloadAfterLandingStrategy)` in `src/main.ts` (router preloading code plus the
    strategy), added by deviation 3 to bring step p95 from 927 ms back under 500 ms. It cannot be
    lazy because the router needs it at bootstrap. This is a warning: the build still passes and
    stays under the 296 kB error budget. The 200 kB **gzip** criterion is the arbiter, enforced by
    `scripts/check-initial-budget.mjs`, and it holds at 91.95 kB gzip. The raw threshold is
    deliberately **not** raised. The warning is kept as tracked debt, to be resolved by trimming
    initial code (for example, moving `@angular/common` i18n formatting out of the shared chunk) or
    by an explicit budget decision, and not by a silent bump.
    W9-T04 update: the diagnostic release line of the footer (`docs/SECURITY_AUDIT.md`, section 7)
    adds 0.58 kB. The warning now reads 292.83 kB raw (829 bytes over). The initial JS is
    92.23 kB gzip. The threshold is still not raised.
