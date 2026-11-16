/**
 * Fails the build if any automatic retry appears in the HTTP layer.
 * contracts/resilience-contract.json states retry.maxRetries = 0: a mutating request
 * must never be replayed without the user's intent.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

const roots = ['../src/app/api', '../src/app/core/http'].map((p) =>
  resolve(import.meta.dirname, p),
);

const forbidden = /retry\(|retryWhen\(|retryBackoff/;

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
        if (forbidden.test(line)) {
          offenders.push(`${full}:${i + 1}: ${line.trim()}`);
        }
      });
  }
};
for (const root of roots) {
  if (existsSync(root)) walk(root);
}

if (offenders.length) {
  console.error(
    'Automatic retry found in the HTTP layer; the resilience contract allows 0 retry.\n' +
      offenders.join('\n'),
  );
  process.exit(1);
}

console.log('check:no-retry OK');
