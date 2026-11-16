import { mkdirSync, writeFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { CONFIRMATION, REFERENCE, goToSummary, json, stubGateway } from '../support/journey-helpers';

/**
 * W9-T02: automated counterpart of the DevTools procedure of `docs/perf-profile.md`.
 *
 * Walks the reference journey RUNS times on the production build (`scripts/perf-server.mjs`) under
 * the mid-tier profile of `lighthouserc.json` (CPU 4x, Slow 4G), and records into
 * `e2e/reports/perf-steps.json`:
 * - the p50/p95/max of every `step:<name>` click-to-paint measure (budget p95 <= 500 ms);
 * - the longest `longtask` entry of each run (budget: none > 200 ms);
 * - the used JS heap after a forced GC on `recapitulatif` (budget < 50 MB);
 * - the number of confirmation POSTs per run after a double click (budget: exactly 1);
 * - the SPA bootstrap: first contentful paint of `/catalogue` minus the end of the download of the
 *   last script requested before it (target 0,3-1,0 s, gateway reads of the first paint included).
 *
 * `/api/v1` answers come from the journey gateway stub; `page.route` fulfilment bypasses network
 * emulation, so each answer is delayed by GATEWAY_RTT_MS (default: the 150 ms Slow 4G RTT).
 */
const RUNS = Number(process.env['PERF_RUNS'] ?? 20);
const GATEWAY_RTT_MS = Number(process.env['GATEWAY_RTT_MS'] ?? 150);
const STEPS = ['souscripteur', 'simulation', 'recapitulatif', 'confirmation'] as const;

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function percentile(values: number[], p: number): number {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}

test.setTimeout(RUNS * 60_000);

test('step navigation profile on the mid-tier device', async ({ browser }) => {
  const durations: Record<string, number[]> = Object.fromEntries(STEPS.map((s) => [s, []]));
  const longestTasks: number[] = [];
  const heapsMb: number[] = [];
  const confirmationsPerRun: number[] = [];
  const bootstrapsMs: number[] = [];

  for (let run = 0; run < RUNS; run += 1) {
    const context = await browser.newContext({ viewport: { width: 412, height: 823 } });
    const page = await context.newPage();
    const cdp = await context.newCDPSession(page);
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
    await cdp.send('Network.enable');
    await cdp.send('Network.emulateNetworkConditions', {
      offline: false,
      latency: 150,
      downloadThroughput: (1638.4 * 1024) / 8,
      uploadThroughput: (675 * 1024) / 8,
    });
    await page.addInitScript(() => {
      const tasks: number[] = [];
      (window as unknown as { __longTasks: number[] }).__longTasks = tasks;
      new PerformanceObserver((list) => {
        for (const entry of list.getEntries()) tasks.push(entry.duration);
      }).observe({ type: 'longtask', buffered: true });
    });

    let confirmations = 0;
    await stubGateway(page, {
      confirmDraft: async (route) => {
        confirmations += 1;
        await json(route, 201, CONFIRMATION);
      },
    });
    // Registered last, so it runs first: models the gateway round trip, then hands over to the stub.
    await page.route('**/api/v1/**', async (route) => {
      await sleep(GATEWAY_RTT_MS);
      await route.fallback();
    });

    await goToSummary(page);

    bootstrapsMs.push(
      await page.evaluate(() => {
        const [navigation] = performance.getEntriesByType('navigation') as PerformanceNavigationTiming[];
        const fcp = performance.getEntriesByName('first-contentful-paint')[0]?.startTime ?? 0;
        const scripts = (performance.getEntriesByType('resource') as PerformanceResourceTiming[]).filter(
          (r) => r.initiatorType === 'script' && r.startTime < fcp,
        );
        return fcp - Math.max(navigation.responseEnd, ...scripts.map((r) => r.responseEnd));
      }),
    );
    await expect(page.locator('main#contenu h1')).toHaveText('Récapitulatif de la demande');

    await cdp.send('HeapProfiler.enable');
    await cdp.send('HeapProfiler.collectGarbage');
    const { usedSize } = (await cdp.send('Runtime.getHeapUsage')) as { usedSize: number };
    heapsMb.push(usedSize / 1024 / 1024);

    await page.getByRole('button', { name: 'Confirmer la souscription >' }).dblclick();
    await expect(page.locator('#reference')).toHaveText(REFERENCE);

    await expect
      .poll(() => page.evaluate(() => performance.getEntriesByType('measure').filter((e) => e.name.startsWith('step:')).length))
      .toBe(STEPS.length);
    const measures = await page.evaluate(() =>
      performance
        .getEntriesByType('measure')
        .filter((e) => e.name.startsWith('step:'))
        .map((e) => ({ name: e.name.slice('step:'.length), duration: e.duration })),
    );
    for (const { name, duration } of measures) durations[name]?.push(duration);
    const tasks = await page.evaluate(() => (window as unknown as { __longTasks: number[] }).__longTasks);
    longestTasks.push(Math.max(0, ...tasks));
    confirmationsPerRun.push(confirmations);

    await context.close();
  }

  const round = (n: number) => Math.round(n * 10) / 10;
  const report = {
    runs: RUNS,
    profile: { cpuSlowdown: 4, network: 'Slow 4G (150 ms RTT, 1.6 Mbps)', gatewayRttMs: GATEWAY_RTT_MS },
    steps: Object.fromEntries(
      STEPS.map((s) => [
        s,
        { samples: durations[s].length, p50: round(percentile(durations[s], 50)), p95: round(percentile(durations[s], 95)), max: round(Math.max(...durations[s])) },
      ]),
    ),
    bootstrapMs: { p50: round(percentile(bootstrapsMs, 50)), p95: round(percentile(bootstrapsMs, 95)) },
    longestTaskMs: round(Math.max(...longestTasks)),
    heapMb: { max: round(Math.max(...heapsMb)), p50: round(percentile(heapsMb, 50)) },
    confirmationsPerRun: { min: Math.min(...confirmationsPerRun), max: Math.max(...confirmationsPerRun) },
  };
  mkdirSync('e2e/reports', { recursive: true });
  writeFileSync('e2e/reports/perf-steps.json', `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify(report, null, 2));

  for (const s of STEPS) expect(durations[s]).toHaveLength(RUNS);
  expect(report.confirmationsPerRun).toEqual({ min: 1, max: 1 });
});
