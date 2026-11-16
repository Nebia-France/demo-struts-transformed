/**
 * W9-T05 guard (`npm run check:no-struts`): the SPA and its deployment descriptors must not
 * reference any artifact of the legacy front controller, which is removed in plan 46. A
 * surviving reference would become a dead link or a 404.
 *
 * Every file under `src/` and `deploy/` is scanned for a `.do` path, the word `struts`, or a
 * `.jsp` / `.jspf` view. The Struts -> Angular correspondence lives in `README.md` instead.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const FORBIDDEN = /\.do\b|struts|\.jspf?\b/i;

const offenders = [];
const walk = (dir) => {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      walk(full);
      continue;
    }
    readFileSync(full, 'utf8')
      .split('\n')
      .forEach((line, i) => {
        if (FORBIDDEN.test(line)) offenders.push(`${relative(root, full)}:${i + 1}: ${line.trim()}`);
      });
  }
};
walk(join(root, 'src'));
walk(join(root, 'deploy'));

if (offenders.length) {
  console.error(
    'Legacy front-controller reference found (.do, struts, .jsp, .jspf): use the target route or API path.\n' +
      offenders.join('\n'),
  );
  process.exit(1);
}
console.log('check:no-struts OK');
