# Step transition performance profile (W7-T02 / W7-T03)

Rendering and validation CPU that the Struts/JSP monolith spent server-side now runs on the
user's device (NFG-006). This document is the measurement procedure for that transfer and the
thresholds recorded for each run.

## What is measured

Each wizard step transition lays User Timing entries (`src/app/core/telemetry/step-marks.ts`):

| Entry | Laid by |
| --- | --- |
| `step:<name>:click` | `markStepClick(<name>)`, in the submit action that starts the transition |
| `step:<name>` | `measureStepPaint(<name>)`, in the `afterNextRender` of the destination step |

`<name>` is the destination route segment: `souscripteur`, `simulation`, `recapitulatif`,
`confirmation`. The duration of `step:<name>` is the click-to-paint time, which replaces the
three local server hops of the legacy wizard. Budget: **p95 <= 500 ms**.

Each measure carries a `detail` holding the trace id of the `traceparent` header sent with the
step request (plan 37), so a client measure joins the gateway access log line of the same
transition by request identifier.

## Device profile

Measurements are taken on the mid-tier profile already used by the Lighthouse run
(`lighthouserc.json`): mobile form factor, 412x823 at DPR 1.75, Slow 4G throttling
(150 ms RTT, 1.6 Mbps) and `cpuSlowdownMultiplier: 4`. The supported browser matrix of
`.browserslistrc` must stay aligned with this profile: widening it changes the emitted output
(and possibly the polyfills) and invalidates the recorded numbers.

## Procedure (Chrome DevTools)

1. `npm run build` then `node scripts/perf-server.mjs` (serves `dist/` on port 4300, the same
   artifact Lighthouse measures — never `ng serve`).
2. Open an incognito window on `http://localhost:4300/catalogue`.
3. DevTools > Performance: enable *Screenshots* and *Memory*, set CPU throttling to **4x** and
   network to **Slow 4G**.
4. Record, then walk the reference journey `Parcours complet jusqu'au recapitulatif` of
   `e2e/features/parcours-souscription.feature`: product > `souscripteur` > `simulation`
   (recalculate, then continue) > `recapitulatif` > confirm.
5. Stop the recording and read the **Timings** track: one `step:<name>` band per transition.
   Repeat the journey 20 times (reload between runs) and take the p95 per step. The same
   entries are readable without DevTools via
   `performance.getEntriesByType('measure').filter((e) => e.name.startsWith('step:'))`.
6. DevTools > Memory: take a heap snapshot on the `recapitulatif` step, at the end of the
   journey.

The automated counterpart of this procedure is `npm run perf:steps`
(`e2e/perf/step-navigation.spec.ts`); the recorded values are in `docs/BENCHMARK.md`.

## Thresholds to record

| Threshold | Value | Where it is read |
| --- | --- | --- |
| Client JS heap | **< 50 MB** | Memory panel heap snapshot on `recapitulatif` |
| Long tasks | **none > 200 ms** | Performance panel, Main track, red-cornered tasks |
| Server rendering CPU for this tier | **0** | No server render path exists: the tier is a static web server |
| Static container footprint | **10-30 MB RSS** (JVM baseline: 256-512 MB) | `docker stats` on the nginx container of `Dockerfile` |

A run is recorded as a pass only when the four rows above hold and the p95 of every
`step:<name>` is <= 500 ms.

## Zoneless rendering

`zone.js` is absent from `package.json` and `angular.json`, and `app.config.ts` declares
`provideZonelessChangeDetection()` (plan 01). Consequently no component may rely on implicit
zone-triggered change detection: rendered state is held in signals, and every `@for` declares a
`track` expression so rows are reused instead of re-created. `npm run check:track`
(`scripts/check-track.mjs`, wired into `lint`) fails the build if a `@for` loses its `track`.

## Telemetry injection check (plan 36)

The error reporting path is verified end to end once per release, because a collector that
receives nothing is indistinguishable from an application that raises nothing.

1. Serve the production artifact (`npm run build`, then `node scripts/perf-server.mjs`) with a
   `runtime-config.json` whose `telemetry.collectorUrl` points at the collector under test and
   `telemetry.enabled` is `true`.
2. Open `http://localhost:4300/catalogue`, then in the console raise an uncaught error:
   `setTimeout(() => { throw new Error('telemetry smoke'); })`. The SPA routes to `/erreur/500`.
3. In the collector, assert the payload: `kind: "error"`, `route` equal to the route the error was
   raised on, and `release` equal to the `release` field of `/version.json`. Assert the serialized
   payload carries no subscriber field and no CSRF token.
4. Walk the reference journey once and assert the `vital` payloads (LCP, INP, CLS) on `/catalogue`
   and `/souscription/confirmation`, and the `journey` payloads up to `confirmation-succeeded`.
5. Set `telemetry.enabled` to `false`, reload, repeat step 2 and assert the collector receives
   nothing.

Record the release, the date and the observed payload count here on every run. The automated
counterpart is `e2e/operational/telemetry.feature` (steps in `e2e/steps/telemetry.steps.ts`,
`npm run e2e:operational`; the collector is the browser's `/telemetry/**` route receiving the
beacons) and `src/app/core/telemetry/telemetry.service.spec.ts`.

| Release | Date | Error payloads | Vitals | Journey | Disabled run sends nothing |
| --- | --- | --- | --- | --- | --- |
| _to record_ | | | | | |
