/**
 * W9-T03 guard (`npm run check:audit`, wired into `lint`): fails the build when
 * `docs/SECURITY_AUDIT.md` does not record the six audit sections of the delivered version.
 *
 * Each section is a `## ` heading matching one of the patterns below and must carry a
 * `**Result:**` line, so an empty placeholder heading does not pass. The DAST section must also
 * state its zero-XSS outcome (`0 XSS finding`), which is the pass criterion of `deploy/dast.md`.
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const file = resolve(import.meta.dirname, '../docs/SECURITY_AUDIT.md');

const SECTIONS = [
  { name: 'npm audit', heading: /npm audit/i },
  { name: 'image scan', heading: /image scan/i },
  { name: 'DAST', heading: /\bDAST\b/ },
  { name: 'headers', heading: /header/i },
  { name: 'CSRF', heading: /\bCSRF\b/ },
  { name: 'API validation', heading: /API validation/i },
];

if (!existsSync(file)) {
  console.error('check:audit: docs/SECURITY_AUDIT.md is missing');
  process.exit(1);
}

// Split on level-2 headings: [heading, body] pairs.
const sections = readFileSync(file, 'utf8')
  .split(/^## /m)
  .slice(1)
  .map((chunk) => {
    const [heading, ...body] = chunk.split('\n');
    return { heading, body: body.join('\n') };
  });

const problems = [];
for (const expected of SECTIONS) {
  const section = sections.find((candidate) => expected.heading.test(candidate.heading));
  if (!section) {
    problems.push(`missing section: ${expected.name}`);
    continue;
  }
  if (!/^\*\*Result:\*\*\s*\S/m.test(section.body)) {
    problems.push(`section "${section.heading.trim()}" has no **Result:** line`);
  }
  if (expected.name === 'DAST' && !/\b0 XSS findings?\b/i.test(section.body)) {
    problems.push('DAST section does not report 0 XSS finding');
  }
}

if (problems.length > 0) {
  console.error('check:audit: docs/SECURITY_AUDIT.md is incomplete:');
  for (const problem of problems) console.error(`  - ${problem}`);
  process.exit(1);
}
console.log(`check:audit: docs/SECURITY_AUDIT.md records the ${SECTIONS.length} audit sections`);
