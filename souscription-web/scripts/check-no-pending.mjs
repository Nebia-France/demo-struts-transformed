/**
 * Plan 41 guard: no step definition of the e2e suite may return 'pending'.
 *
 * Every `e2e/steps/*.steps.ts` file is scanned, whether it backs one of the 88 declared scenarios
 * (SPA tier of `e2e/SCENARIO_ALLOCATION.md`) or an operational scenario of `e2e/operational/`.
 * The cucumber-js runs are strict, so a pending step fails them anyway; this guard also catches a
 * pending step no scenario reaches, which would otherwise be a silent quarantine.
 */
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join, resolve } from 'node:path';

const require = createRequire(import.meta.url);
const { spaRows } = require('../e2e/allocation.cjs');

const root = resolve(import.meta.dirname, '..');
const PENDING = /\breturn\s+(['"`])pending\1/;

const stepFiles = new Set(
  readdirSync(join(root, 'e2e/steps'))
    .filter((f) => f.endsWith('.steps.ts'))
    .map((f) => `e2e/steps/${f}`),
);

const errors = [];
for (const row of spaRows()) {
  for (const path of row.tests) {
    if (/^e2e\/steps\/.+\.steps\.ts$/.test(path) && !existsSync(join(root, path))) {
      errors.push(`${path}: referenced by an SPA-allocated scenario but missing`);
    }
  }
}
for (const path of [...stepFiles].sort()) {
  readFileSync(join(root, path), 'utf8')
    .split(/\r?\n/)
    .forEach((line, i) => {
      if (PENDING.test(line)) errors.push(`${path}:${i + 1}: returns 'pending'`);
    });
}

if (errors.length) {
  console.error(`check-no-pending: ${errors.length} pending or missing step file(s)`);
  for (const e of errors) console.error(`  - ${e}`);
  process.exit(1);
}
console.log(`check-no-pending: ${stepFiles.size} step files, no pending step`);
