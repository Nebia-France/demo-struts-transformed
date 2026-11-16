/**
 * W7-T03 guard: fails the build when a `@for` block does not declare a `track` expression.
 *
 * Under zoneless change detection an untracked `@for` re-creates its DOM nodes on every list
 * identity change, which shows up as a long task on the step transition being measured
 * (docs/perf-profile.md). The Angular compiler already rejects a missing `track`, but it does so
 * only for templates it compiles; this check covers every `src/**\/*.html` file, including the
 * ones a route no longer loads, so the invariant cannot drift back in.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../src');

const FOR_OPENER = /@for\s*\(/g;

/** Header of a `@for`, from its opening parenthesis to the matching one, calls included. */
function header(html, open) {
  let depth = 0;
  for (let i = open; i < html.length; i += 1) {
    if (html[i] === '(') depth += 1;
    else if (html[i] === ')') {
      depth -= 1;
      if (depth === 0) return html.slice(open + 1, i);
    }
  }
  return html.slice(open + 1);
}

const offenders = [];
const walk = (dir) => {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      walk(full);
      continue;
    }
    if (!full.endsWith('.html')) continue;
    const html = readFileSync(full, 'utf8');
    for (const match of html.matchAll(FOR_OPENER)) {
      const open = match.index + match[0].length - 1;
      const expr = header(html, open);
      if (/\btrack\b/.test(expr)) continue;
      const line = html.slice(0, match.index).split('\n').length;
      offenders.push(`${full}:${line}: @for (${expr.trim()})`);
    }
  }
};
walk(root);

if (offenders.length) {
  console.error(
    'A @for block has no track expression: add `track <identity>` so the rows are reused.\n' +
      offenders.join('\n'),
  );
  process.exit(1);
}

console.log('check:track OK');
