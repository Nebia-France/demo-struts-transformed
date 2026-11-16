/**
 * Fails the build if any journey data can live only in the browser.
 *
 * The server-side draft is authoritative and every step transition is persisted through the
 * gateway, so the SPA stays a replicable static artifact without server affinity.
 * `localStorage` would survive the journey and reintroduce client-only state; the single
 * allowed browser write is the draft reference in `src/app/wizard/draft-storage.ts`
 * (`sessionStorage`, draftId + createdAt only).
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../src');

const forbidden = /localStorage/;

const offenders = [];
const walk = (dir) => {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      walk(full);
      continue;
    }
    if (!/\.(ts|html|scss)$/.test(full)) continue;
    readFileSync(full, 'utf8')
      .split('\n')
      .forEach((line, i) => {
        if (forbidden.test(line)) {
          offenders.push(`${full}:${i + 1}: ${line.trim()}`);
        }
      });
  }
};
walk(root);

if (offenders.length) {
  console.error(
    'localStorage found under src/: no journey data may live only in the browser.\n' +
      offenders.join('\n'),
  );
  process.exit(1);
}

console.log('check:no-localstorage OK');
