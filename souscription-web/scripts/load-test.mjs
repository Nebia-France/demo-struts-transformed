// Closed-loop load test of the benchmark (W9-T02, docs/BENCHMARK.md).
//
// CONCURRENCY virtual users each issue GET requests back to back for DURATION_S seconds against
// TARGET, cycling through PATHS (the read operations of the SPA first paint by default). The
// p50/p95/p99 latencies are written to e2e/reports/load-<LABEL>.json. The run fails when a request
// errors or when p99 reaches the 10 s client timeout of `timeoutInterceptor`.
//
//   TARGET=https://<spa-origin> npm run perf:load          # gateway behind the shared ingress
import { mkdirSync, writeFileSync } from 'node:fs';

const TARGET = process.env.TARGET ?? 'http://localhost:18080';
const PATHS = (process.env.PATHS ?? '/api/v1/csrf-token,/api/v1/products').split(',');
const CONCURRENCY = Number(process.env.CONCURRENCY ?? 50);
const DURATION_S = Number(process.env.DURATION_S ?? 30);
const LABEL = process.env.LABEL ?? 'gateway';
const CLIENT_TIMEOUT_MS = 10_000;

const latencies = [];
const statuses = {};
let errors = 0;
const deadline = Date.now() + DURATION_S * 1000;

async function user(offset) {
  for (let i = offset; Date.now() < deadline; i += 1) {
    const url = TARGET + PATHS[i % PATHS.length];
    const start = performance.now();
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(CLIENT_TIMEOUT_MS) });
      await res.arrayBuffer();
      statuses[res.status] = (statuses[res.status] ?? 0) + 1;
      if (res.status >= 500) errors += 1;
    } catch {
      errors += 1;
    }
    latencies.push(performance.now() - start);
  }
}

await Promise.all(Array.from({ length: CONCURRENCY }, (_, i) => user(i)));

latencies.sort((a, b) => a - b);
const pct = (p) => Math.round(latencies[Math.min(latencies.length - 1, Math.ceil((p / 100) * latencies.length) - 1)] * 10) / 10;
const report = {
  target: TARGET,
  paths: PATHS,
  concurrency: CONCURRENCY,
  durationS: DURATION_S,
  requests: latencies.length,
  rps: Math.round(latencies.length / DURATION_S),
  statuses,
  errors,
  p50: pct(50),
  p95: pct(95),
  p99: pct(99),
  max: pct(100),
};
mkdirSync('e2e/reports', { recursive: true });
writeFileSync(`e2e/reports/load-${LABEL}.json`, `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify(report, null, 2));

if (errors > 0 || report.p99 >= CLIENT_TIMEOUT_MS) {
  console.error(`load-test: ${errors} error(s), p99 ${report.p99} ms (client timeout ${CLIENT_TIMEOUT_MS} ms)`);
  process.exit(1);
}
