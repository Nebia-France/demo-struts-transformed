/**
 * Plan 45 guard (`npm run check:contract`, wired into `lint`): fails the build when
 * `docs/CONTRACT_VALIDATION.md` does not record the consumer contract validation, or when the
 * production configuration would ship the msw gateway stub.
 *
 * - the four `## ` sections (operations, resilience, security, versioning) must exist and each
 *   carry a `**Result:**` line, so an empty placeholder heading does not pass;
 * - the operations section must name the 10 consumed operations;
 * - the resilience section must state measured p95 and p99 values in ms;
 * - `NG_APP_USE_GATEWAY_STUB` must be `"false"` in the production configuration of angular.json.
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const file = resolve(root, 'docs/CONTRACT_VALIDATION.md');
const problems = [];

const OPERATIONS = [
  'issueCsrfToken',
  'listProducts',
  'getProduct',
  'simulate',
  'startDraft',
  'getDraft',
  'putDraftSubscriber',
  'putDraftSimulation',
  'confirmDraft',
  'getSubscription',
];

const SECTIONS = [
  { name: 'operations', heading: /\boperations\b/i },
  { name: 'resilience', heading: /\bresilience\b/i },
  { name: 'security', heading: /\bsecurity\b/i },
  { name: 'versioning', heading: /\bversioning\b/i },
];

if (!existsSync(file)) {
  problems.push('docs/CONTRACT_VALIDATION.md is missing');
} else {
  const sections = readFileSync(file, 'utf8')
    .split(/^## /m)
    .slice(1)
    .map((chunk) => {
      const [heading, ...body] = chunk.split('\n');
      return { heading, body: body.join('\n') };
    });
  for (const expected of SECTIONS) {
    const section = sections.find((candidate) => expected.heading.test(candidate.heading));
    if (!section) {
      problems.push(`missing section: ${expected.name}`);
      continue;
    }
    if (!/^\*\*Result:\*\*\s*\S/m.test(section.body)) {
      problems.push(`section "${section.heading.trim()}" has no **Result:** line`);
    }
    if (expected.name === 'operations') {
      const missing = OPERATIONS.filter((op) => !section.body.includes(`\`${op}\``));
      if (missing.length) problems.push(`operations section does not record: ${missing.join(', ')}`);
    }
    if (expected.name === 'resilience') {
      for (const p of ['p95', 'p99']) {
        if (!new RegExp(`\\b${p}\\s+\\d+(?:\\.\\d+)?\\s*ms\\b`, 'i').test(section.body)) {
          problems.push(`resilience section has no measured ${p} value ("${p} <n> ms")`);
        }
      }
    }
  }
}

const angular = JSON.parse(readFileSync(resolve(root, 'angular.json'), 'utf8'));
const production = angular.projects?.['souscription-web']?.architect?.build?.configurations?.production;
const flag = production?.define?.['import.meta.env.NG_APP_USE_GATEWAY_STUB'];
if (flag !== '"false"') {
  problems.push(`production configuration sets NG_APP_USE_GATEWAY_STUB to ${flag ?? 'nothing'}, expected "false"`);
}

if (problems.length > 0) {
  console.error('check:contract: consumer contract validation incomplete:');
  for (const problem of problems) console.error(`  - ${problem}`);
  process.exit(1);
}
console.log(
  `check:contract: docs/CONTRACT_VALIDATION.md records the ${SECTIONS.length} sections; production stub flag is "false"`,
);
