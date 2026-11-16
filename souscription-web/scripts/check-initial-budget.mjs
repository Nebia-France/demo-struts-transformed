// Initial JS budget gate, expressed in the unit the criterion uses: gzip transfer size.
// The `initial` budget of `angular.json` only counts raw bytes, so it cannot express
// "initial JS <= 200 kB gzip". This gate walks the **static** import graph from the entry
// script of the emitted `index.html` -- which is exactly what the browser downloads before
// the first paint, dynamic `import()` chunks excluded -- and sums the `.gz` variants produced
// by `scripts/precompress.mjs`. It runs after the build and fails it above the threshold.
import { readFileSync, statSync } from 'node:fs';
import { dirname, join, normalize } from 'node:path';

const OUTPUT_DIR = 'dist/souscription-web/browser';
const MAX_GZIP_BYTES = 200 * 1024;

const html = readFileSync(join(OUTPUT_DIR, 'index.html'), 'utf8');
const entry = /<script[^>]+src="([^"]+\.js)"/.exec(html)?.[1];

if (!entry) {
  console.error(`check-initial-budget: no entry script found in ${OUTPUT_DIR}/index.html`);
  process.exit(1);
}

/** Static import specifiers of an emitted chunk; a dynamic `import(` is deliberately skipped. */
function staticImports(file) {
  const source = readFileSync(join(OUTPUT_DIR, file), 'utf8');
  const base = dirname(file);
  return [...source.matchAll(/(.)"(\.{1,2}\/[^"]+\.js)"/g)]
    .filter(([, before]) => before !== '(')
    .map(([, , specifier]) => normalize(join(base, specifier)));
}

const initial = new Set();
const queue = [entry];
while (queue.length > 0) {
  const file = queue.pop();
  if (initial.has(file)) {
    continue;
  }
  initial.add(file);
  queue.push(...staticImports(file));
}

let total = 0;
for (const file of initial) {
  const gzip = join(OUTPUT_DIR, `${file}.gz`);
  // A chunk below the precompression floor has no `.gz` variant: count its raw size.
  const path = statSync(gzip, { throwIfNoEntry: false }) ? gzip : join(OUTPUT_DIR, file);
  total += statSync(path).size;
}

const kb = (total / 1024).toFixed(2);
if (total > MAX_GZIP_BYTES) {
  console.error(
    `check-initial-budget: initial JS ${kb} kB gzip over ${initial.size} chunk(s) exceeds the ${MAX_GZIP_BYTES / 1024} kB budget`,
  );
  process.exit(1);
}

console.log(
  `check-initial-budget: initial JS ${kb} kB gzip over ${initial.size} chunk(s) (budget ${MAX_GZIP_BYTES / 1024} kB)`,
);
