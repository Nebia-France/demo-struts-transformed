/**
 * Fails the build when a class-based router guard or HTTP interceptor is introduced.
 * The target stack uses only functional `CanActivateFn` guards and `HttpInterceptorFn`
 * interceptors; the class-based APIs are deprecated and bypass the DI rules of plan 12.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../src');

const forbidden = [
  /implements\s+CanActivate\b/,
  /implements\s+HttpInterceptor\b/,
  /class\s+\w*Guard\b/,
];

const offenders = [];
const walk = (dir) => {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      walk(full);
      continue;
    }
    if (!full.endsWith('.ts')) continue;
    readFileSync(full, 'utf8')
      .split('\n')
      .forEach((line, i) => {
        if (forbidden.some((re) => re.test(line))) {
          offenders.push(`${full}:${i + 1}: ${line.trim()}`);
        }
      });
  }
};
walk(root);

if (offenders.length) {
  console.error(
    'Class-based guard or interceptor found; only functional guards and interceptors are allowed.\n' +
      offenders.join('\n'),
  );
  process.exit(1);
}

console.log('check:functional OK');
