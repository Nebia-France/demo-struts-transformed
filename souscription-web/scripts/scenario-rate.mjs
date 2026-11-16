/**
 * Plan 41 (W9-T01): green-scenario rate of the full Gherkin replay, written to
 * `e2e/reports/scenario-rate.json`.
 *
 * Merges the two reports of one pipeline run:
 * - the SPA/e2e tier: cucumber-js JSON report of `npm run e2e` (`e2e/reports/cucumber.json`);
 * - the backend tier: Cucumber-JVM JSON report of `mvn test` at the repository root
 *   (`target/cucumber-reports/backend.json`, overridable with `BACKEND_CUCUMBER_REPORT`).
 *
 * Each of the 88 declared scenarios is read from the report of the tier `e2e/SCENARIO_ALLOCATION.md`
 * allocates it to (`e2e/allocation.cjs`). A scenario is green when its report holds it and every
 * row it expands to (one per `Examples` line for an outline) passed every step, hooks included.
 * `scripts/check-scenario-rate.mjs` fails when `green < declared`.
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join, relative, resolve } from 'node:path';

const require = createRequire(import.meta.url);
const { readAllocation } = require('../e2e/allocation.cjs');

const root = resolve(import.meta.dirname, '..');
const repository = resolve(root, '../..');
const reportsDir = join(root, 'e2e/reports');
const outputFile = join(reportsDir, 'scenario-rate.json');
const TIERS = {
  spa: {
    runner: 'cucumber-js (npm run e2e)',
    report: join(reportsDir, 'cucumber.json'),
  },
  backend: {
    runner: 'cucumber-jvm (mvn test, fr.demo.souscription.acceptance.BackendAcceptanceTest)',
    report: resolve(process.env.BACKEND_CUCUMBER_REPORT ?? join(repository, 'target/cucumber-reports/backend.json')),
  },
};

/** title -> { rows, passed } of every scenario in a cucumber JSON report. */
function readReport(file) {
  const outcome = new Map();
  if (!existsSync(file)) return outcome;
  for (const feature of JSON.parse(readFileSync(file, 'utf8'))) {
    for (const element of feature.elements ?? []) {
      if (element.type !== 'scenario') continue;
      const results = [...(element.before ?? []), ...(element.steps ?? []), ...(element.after ?? [])];
      const passed = results.length > 0 && results.every((step) => step.result?.status === 'passed');
      const current = outcome.get(element.name) ?? { rows: 0, passed: 0 };
      current.rows++;
      if (passed) current.passed++;
      outcome.set(element.name, current);
    }
  }
  return outcome;
}

const rows = readAllocation();
const tiers = {};
const scenarios = [];
for (const [tier, { runner, report }] of Object.entries(TIERS)) {
  const outcome = readReport(report);
  const allocated = rows.filter((row) => (tier === 'spa' ? row.spa : !row.spa));
  let green = 0;
  let pickles = 0;
  let picklesPassed = 0;
  for (const row of allocated) {
    const result = outcome.get(row.title) ?? { rows: 0, passed: 0 };
    const isGreen = result.rows > 0 && result.passed === result.rows;
    if (isGreen) green++;
    pickles += result.rows;
    picklesPassed += result.passed;
    scenarios.push({
      index: row.index,
      domain: row.feature.replace(/\.feature$/, ''),
      title: row.title,
      tier,
      rows: result.rows,
      green: isGreen,
    });
  }
  tiers[tier] = {
    runner,
    report: relative(repository, report),
    reportFound: existsSync(report),
    allocated: allocated.length,
    green,
    pickles,
    picklesPassed,
  };
}
scenarios.sort((a, b) => a.index - b.index);

const domains = {};
for (const scenario of scenarios) {
  const domain = (domains[scenario.domain] ??= { declared: 0, green: 0 });
  domain.declared++;
  if (scenario.green) domain.green++;
}

const green = scenarios.filter((s) => s.green).length;
const rate = {
  green,
  declared: rows.length,
  allocatedToSpa: tiers.spa.allocated,
  allocatedToBackend: tiers.backend.allocated,
  environment: {
    spa: process.env.E2E_BASE_URL ?? 'local e2e-server serving dist/ with the deploy/nginx.conf headers',
    gatewayForSpaTier: 'contract-shaped gateway double (e2e/support/fake-gateway.ts)',
    backendTier: 'legacy reference implementation of this repository (root pom)',
  },
  tiers,
  domains,
  red: scenarios.filter((s) => !s.green).map((s) => `${s.index} ${s.title} [${s.tier}]`),
  scenarios,
};

mkdirSync(reportsDir, { recursive: true });
writeFileSync(outputFile, `${JSON.stringify(rate, null, 2)}\n`);
console.log(
  `scenario-rate: ${green}/${rows.length} green ` +
    `(spa ${tiers.spa.green}/${tiers.spa.allocated}, backend ${tiers.backend.green}/${tiers.backend.allocated}) ` +
    '-> e2e/reports/scenario-rate.json',
);
