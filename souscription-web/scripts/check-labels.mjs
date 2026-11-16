/**
 * W4-T08 guard: fails the build when a component template renders a hard-coded French
 * sentence instead of a label taken from its feature `labels.ts`.
 *
 * Heuristic: after interpolations, bindings, control-flow blocks, comments and tag markup have
 * been stripped, a residual text node of 3 or more words is an offence when it contains a
 * letter with a diacritic or ends with a `:`. Short fragments (units, separators, acronyms) are
 * out of scope on purpose, so the check stays free of false positives while still catching every
 * sentence that belongs in a label module.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../src');

const DIACRITIC = /[àâäçéèêëîïôöùûüÿœæÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆ]/;

/** Removes everything that is not a literal text node of the rendered output. */
function textNodes(html) {
  return (
    html
      // Angular and HTML comments
      .replace(/<!--[\s\S]*?-->/g, ' ')
      // interpolations
      .replace(/\{\{[\s\S]*?\}\}/g, ' ')
      // control-flow block openers / closers: @if (...) { , } @else { , }
      .replace(/@[a-z]+\s*(\([\s\S]*?\))?\s*\{/g, ' ')
      .replace(/[{}]/g, ' ')
      // tags, with their attributes and bindings
      .replace(/<[^>]*>/g, '\n')
      .split('\n')
  );
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
    const lines = readFileSync(full, 'utf8').split('\n');
    lines.forEach((line, i) => {
      for (const node of textNodes(line)) {
        const text = node.replace(/&[a-z]+;|&#\d+;/g, ' ').trim();
        if (text.split(/\s+/).filter(Boolean).length < 3) continue;
        if (DIACRITIC.test(text) || text.endsWith(':')) {
          offenders.push(`${full}:${i + 1}: ${text}`);
        }
      }
    });
  }
};
walk(root);

if (offenders.length) {
  console.error(
    'Hard-coded text found in a template: move it to the feature labels.ts and render it with {{ }}.\n' +
      offenders.join('\n'),
  );
  process.exit(1);
}

console.log('check:labels OK');
