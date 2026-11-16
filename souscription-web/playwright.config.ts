import { defineConfig } from '@playwright/test';

/**
 * Playwright harness of the browser layers:
 * - `e2e/visual`: the `application.css` -> SCSS visual-regression port (plan 20);
 * - `e2e/a11y`: the axe-core check of the 7 routes (W4-T07);
 * - `e2e/api`: the UI-bypassing negative API tests of `InputValidator` (W6-T05), which need a
 *   running gateway;
 * - `e2e/journeys`: the five journeys of the test pyramid (W8-T01);
 * - `e2e/perf`: the step navigation profile of the benchmark (W9-T02, `npm run perf:steps`);
 * - `e2e/dast`: the reflected/stored XSS pass of `deploy/dast.md` (W9-T03, `npm run dast`);
 * - `e2e/contract`: the consumer contract of the 10 gateway operations (plan 45,
 *   `npm run contract:consumer`), against `CONTRACT_BASE_URL` when a gateway is deployed, else
 *   against the gateway double on the production build.
 *
 * All four are blocking CI steps since plan 38
 * (`.github/workflows/frontend-souscription-web.yml`).
 *
 * `visual`, `a11y` and `api` run on the dev server (`npm start`), whose development configuration
 * serves the msw gateway stub so the wizard routes render without a backend. The journeys run on
 * the production build served by `scripts/perf-server.mjs`: that bundle drops msw, so
 * `page.route('**\/api/v1/**')` is the single, deterministic fault-injection lever
 * (403, 410, 503, held confirmation). Both servers are declared below, so every npm script runs
 * unattended and reuses a server already started by hand.
 *
 * `snapshotPathTemplate` keeps the visual baselines at their existing location.
 */
// Port 4201, not the Angular default 4200, so the suites cannot be pointed at whatever dev
// server already occupies the default port on a developer machine.
const BASE_URL = process.env['BASE_URL'] ?? 'http://localhost:4201';
const JOURNEYS_BASE_URL = process.env['JOURNEYS_BASE_URL'] ?? 'http://localhost:4300';

export default defineConfig({
  testDir: './e2e',
  testMatch: [
    'visual/**/*.spec.ts',
    'a11y/**/*.a11y.spec.ts',
    'api/**/*.spec.ts',
    'journeys/**/*.spec.ts',
    'perf/**/*.spec.ts',
    'dast/**/*.spec.ts',
    'contract/**/*.spec.ts',
  ],
  snapshotPathTemplate: './e2e/visual/__screenshots__/{testFileName}/{arg}{ext}',
  use: {
    baseURL: BASE_URL,
    viewport: { width: 1280, height: 900 },
  },
  projects: [
    {
      name: 'suites',
      testMatch: ['visual/**/*.spec.ts', 'a11y/**/*.a11y.spec.ts', 'api/**/*.spec.ts'],
    },
    {
      name: 'journeys',
      testMatch: ['journeys/**/*.spec.ts'],
      use: { baseURL: JOURNEYS_BASE_URL },
    },
    {
      // W9-T02 step navigation profile (docs/BENCHMARK.md), same production build as the journeys.
      name: 'perf',
      testMatch: ['perf/**/*.spec.ts'],
      use: { baseURL: JOURNEYS_BASE_URL },
    },
    {
      // W9-T03 DAST pass (deploy/dast.md, docs/SECURITY_AUDIT.md), same production build.
      name: 'dast',
      testMatch: ['dast/**/*.spec.ts'],
      use: { baseURL: JOURNEYS_BASE_URL },
    },
    {
      // Plan 45 consumer contract (docs/CONTRACT_VALIDATION.md), same production build.
      name: 'contract',
      testMatch: ['contract/**/*.spec.ts'],
      use: { baseURL: JOURNEYS_BASE_URL },
    },
  ],
  webServer: [
    {
      command: 'npm start -- --port 4201',
      url: BASE_URL,
      reuseExistingServer: true,
      timeout: 180_000,
    },
    {
      command: 'npm run build && node scripts/perf-server.mjs',
      url: JOURNEYS_BASE_URL,
      reuseExistingServer: true,
      timeout: 300_000,
    },
  ],
  // `list` on the console; the HTML report is uploaded by CI. PLAYWRIGHT_REPORT_DIR keeps one
  // report per suite (playwright-report/<suite>) instead of each run overwriting the previous one.
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: process.env['PLAYWRIGHT_REPORT_DIR'] ?? 'playwright-report' }],
  ],
});
