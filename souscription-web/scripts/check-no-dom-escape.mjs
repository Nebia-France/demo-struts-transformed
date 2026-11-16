/**
 * W4-T09 guard: the output escaping contract.
 *
 * Every server-provided text is rendered by `{{ }}`, which Angular escapes. Nothing in the SPA
 * may bypass that: no `[innerHTML]` in a template, no `innerHTML` / `outerHTML` assignment, no
 * `bypassSecurityTrust*`, no write through `ElementRef.nativeElement` and no `document.write`.
 * The ESLint `no-restricted-syntax` rules are the compile-time counterpart; this script covers
 * templates as well and prints the assertions it checked so they appear in the lint output.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../src');

const assertions = [
  { name: '[innerHTML] binding in a template', pattern: /\[innerHTML\]/ },
  { name: 'innerHTML / outerHTML assignment', pattern: /\b(inner|outer)HTML\s*=/ },
  { name: 'DomSanitizer.bypassSecurityTrust*', pattern: /bypassSecurityTrust/ },
  { name: 'write through ElementRef.nativeElement', pattern: /nativeElement\s*\./ },
  { name: 'document.write', pattern: /document\s*\.\s*write\b/ },
];

const offenders = [];
const walk = (dir) => {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      walk(full);
      continue;
    }
    if (!/\.(ts|html)$/.test(full)) continue;
    readFileSync(full, 'utf8')
      .split('\n')
      .forEach((line, i) => {
        for (const { name, pattern } of assertions) {
          if (pattern.test(line)) {
            offenders.push(`${full}:${i + 1}: ${name}: ${line.trim()}`);
          }
        }
      });
  }
};
walk(root);

if (offenders.length) {
  console.error(
    'Escaping contract violated: server-provided text must only be rendered by {{ }}.\n' +
      offenders.join('\n'),
  );
  process.exit(1);
}

for (const { name } of assertions) {
  console.log(`check:no-dom-escape - no ${name}`);
}
console.log('check:no-dom-escape OK');
