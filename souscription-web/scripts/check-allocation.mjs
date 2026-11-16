/**
 * W8-T02 guard: every Gherkin scenario is allocated to an asserting tier.
 *
 * Parses every `e2e/features/*.feature`, extracts the `Scenario` / `Scenario Outline` titles and
 * fails when a title is absent from `e2e/SCENARIO_ALLOCATION.md`, when an allocated title no
 * longer exists in its feature file, when a row names no tier or no test file, when a referenced
 * SPA test file does not exist, or when the allocated count is not 88.
 */
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const EXPECTED = 88;
const root = resolve(import.meta.dirname, '..');
const featuresDir = join(root, 'e2e/features');
const allocationFile = join(root, 'e2e/SCENARIO_ALLOCATION.md');

const errors = [];

const declared = new Map();
for (const file of readdirSync(featuresDir).filter((f) => f.endsWith('.feature')).sort()) {
  for (const line of readFileSync(join(featuresDir, file), 'utf8').split(/\r?\n/)) {
    const m = /^\s*Scenario(?: Outline)?:\s*(.*?)\s*$/.exec(line);
    if (m) {
      const key = `${file}::${m[1]}`;
      if (declared.has(key)) errors.push(`duplicate scenario title in ${file}: "${m[1]}"`);
      declared.set(key, file);
    }
  }
}

const md = readFileSync(allocationFile, 'utf8');
const start = md.indexOf('<!-- allocation-table:start -->');
const end = md.indexOf('<!-- allocation-table:end -->');
if (start < 0 || end < start) {
  console.error('check-allocation: allocation-table markers not found in SCENARIO_ALLOCATION.md');
  process.exit(1);
}

const allocated = new Map();
for (const line of md.slice(start, end).split(/\r?\n/)) {
  const cells = line.split('|').slice(1, -1).map((c) => c.trim());
  if (cells.length !== 5 || !/^\d+$/.test(cells[0])) continue;
  const [, domain, title, tier, tests] = cells;
  const file = domain.replace(/`/g, '').split('/').pop();
  const key = `${file}::${title}`;
  if (allocated.has(key)) errors.push(`scenario allocated twice: ${file} "${title}"`);
  allocated.set(key, true);
  if (!declared.has(key)) errors.push(`allocated title matches no scenario: ${file} "${title}"`);
  if (!tier) errors.push(`no tier for ${file} "${title}"`);
  if (!tests) errors.push(`no test file for ${file} "${title}"`);
  for (const [, path] of tests.matchAll(/`([^`]+\.(?:ts|mjs|sh))`/g)) {
    if (!existsSync(join(root, path))) errors.push(`test file not found for "${title}": ${path}`);
  }
}

for (const key of declared.keys()) {
  if (!allocated.has(key)) errors.push(`unallocated scenario: ${key.replace('::', ' "')}"`);
}
if (declared.size !== EXPECTED) {
  errors.push(`expected ${EXPECTED} declared scenarios, found ${declared.size}`);
}
if (allocated.size !== EXPECTED) {
  errors.push(`expected ${EXPECTED} allocated scenarios, found ${allocated.size}`);
}

if (errors.length) {
  console.error(`check-allocation: ${errors.length} problem(s)`);
  for (const e of errors) console.error(`  - ${e}`);
  process.exit(1);
}
console.log(
  `check-allocation: ${allocated.size} scenarios allocated, 0 unallocated, titles match byte for byte`,
);
