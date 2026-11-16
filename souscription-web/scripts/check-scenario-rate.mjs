/**
 * Plan 41 (W9-T01) consolidation gate: the full Gherkin replay is green.
 *
 * Fails when `e2e/reports/scenario-rate.json` (written by `scripts/scenario-rate.mjs` after the
 * cucumber-js and Cucumber-JVM runs of one pipeline execution) reports fewer green scenarios than
 * declared, when it no longer matches the current allocation table, or when a domain total differs
 * from the declared one. No exception list: any red scenario fails the gate.
 */
import { existsSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join, resolve } from 'node:path';

const require = createRequire(import.meta.url);
const { readAllocation } = require('../e2e/allocation.cjs');

const root = resolve(import.meta.dirname, '..');
const rateFile = join(root, 'e2e/reports/scenario-rate.json');

/** Scenarios declared per domain by RAPPORT_COUVERTURE_GHERKIN.md. */
const DECLARED_DOMAINS = {
  catalogue: 8,
  'validation-souscripteur': 10,
  simulation: 10,
  'parcours-souscription': 15,
  'confirmation-souscription': 13,
  'persistance-souscriptions': 9,
  'securite-web': 11,
  'initialisation-application': 8,
  'formulaire-souscription': 4,
};
const DECLARED = Object.values(DECLARED_DOMAINS).reduce((sum, n) => sum + n, 0);

const errors = [];
if (!existsSync(rateFile)) {
  console.error('check-scenario-rate: e2e/reports/scenario-rate.json is missing (run mvn test, then npm run e2e)');
  process.exit(1);
}
const rate = JSON.parse(readFileSync(rateFile, 'utf8'));
const rows = readAllocation();

if (rows.length !== DECLARED) errors.push(`allocation table holds ${rows.length} scenarios, ${DECLARED} declared`);
if (rate.declared !== DECLARED) errors.push(`report declares ${rate.declared} scenarios, ${DECLARED} expected`);
if (!(rate.green >= rate.declared)) errors.push(`${rate.green} green < ${rate.declared} declared`);

const reported = new Map((rate.scenarios ?? []).map((s) => [s.title, s]));
for (const row of rows) {
  const scenario = reported.get(row.title);
  const tier = row.spa ? 'spa' : 'backend';
  if (!scenario) {
    errors.push(`#${row.index} "${row.title}" is absent from the report`);
  } else if (scenario.tier !== tier) {
    errors.push(`#${row.index} "${row.title}" was replayed by the ${scenario.tier} tier, allocated to ${tier}`);
  } else if (!scenario.green) {
    errors.push(`#${row.index} "${row.title}" is red (${tier} tier)`);
  }
}
if (reported.size !== rows.length) errors.push(`report lists ${reported.size} scenarios, allocation ${rows.length}`);

for (const [domain, declared] of Object.entries(DECLARED_DOMAINS)) {
  const counts = rate.domains?.[domain];
  if (!counts) {
    errors.push(`domain ${domain} is absent from the report`);
    continue;
  }
  if (counts.declared !== declared) errors.push(`domain ${domain}: ${counts.declared} scenarios, ${declared} declared`);
  if (counts.green !== declared) errors.push(`domain ${domain}: ${counts.green}/${declared} green`);
}

for (const [tier, counts] of Object.entries(rate.tiers ?? {})) {
  console.log(
    `  ${tier.padEnd(8)} ${counts.green}/${counts.allocated} scenarios green, ` +
      `${counts.picklesPassed}/${counts.pickles} rows passed (${counts.runner})`,
  );
}

if (errors.length) {
  console.error(`check-scenario-rate: ${errors.length} problem(s)`);
  for (const e of errors) console.error(`  - ${e}`);
  process.exit(1);
}
console.log(`check-scenario-rate: ${rate.green}/${rate.declared} scenarios green, every domain at its declared total`);
