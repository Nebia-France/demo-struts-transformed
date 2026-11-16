// Build-time precompression (W7-T01).
// Produces a `.gz` variant next to every `.js` and `.css` file of the production
// output, so that nginx answers with `gzip_static` instead of compressing on
// each request. Compression happens once at build time at the highest level, which the runtime
// could not afford.
import { gzipSync } from 'node:zlib';
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, extname } from 'node:path';

const OUTPUT_DIR = 'dist/souscription-web/browser';
const EXTENSIONS = new Set(['.js', '.css']);
// Files below this size do not benefit from a precompressed variant.
const MIN_BYTES = 256;

function collect(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      return collect(path);
    }
    return EXTENSIONS.has(extname(entry.name)) ? [path] : [];
  });
}

const files = collect(OUTPUT_DIR);
let written = 0;

for (const file of files) {
  const source = readFileSync(file);
  if (statSync(file).size < MIN_BYTES) {
    continue;
  }
  writeFileSync(`${file}.gz`, gzipSync(source, { level: 9 }));
  written += 1;
}

if (written === 0) {
  console.error(`precompress: no .js/.css file found under ${OUTPUT_DIR}`);
  process.exit(1);
}

console.log(`precompress: ${written} file(s) compressed to .gz in ${OUTPUT_DIR}`);
