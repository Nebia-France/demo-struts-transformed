/**
 * Fails the build if a service worker or an API response cache is introduced.
 *
 * The cache policy is owned by the edge: hashed assets are `immutable`, `index.html` and
 * `version.json` are `no-store`, and `/api/v1` JSON responses are `no-store` as emitted by the
 * gateway. A service worker (`@angular/service-worker`, `ngsw-config.json`, an `angular.json`
 * `serviceWorker` option) or a client-side response cache (`shareReplay` on an API observable)
 * would serve stale documents or stale subscription data from the browser and silently defeat
 * that policy.
 */
import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs';
import { join, resolve, relative } from 'node:path';

const project = resolve(import.meta.dirname, '..');

const offenders = [];

// 1. a service worker must not be declared anywhere in the project configuration or sources
const forbidden = /serviceWorker|@angular\/service-worker|ngsw-config/;

// 2. an API response must not be memoised in the client
const forbiddenApi = /\bshareReplay\b|\bHttpCache\b/;

const scan = (dir, { skip = [] } = {}) => {
  for (const entry of readdirSync(dir)) {
    if (skip.includes(entry)) continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      scan(full, { skip });
      continue;
    }
    if (!/\.(ts|html|scss|json|mjs|js)$/.test(full)) continue;
    const isApi = relative(project, full).startsWith(join('src', 'app', 'api'));
    readFileSync(full, 'utf8')
      .split('\n')
      .forEach((line, i) => {
        if (forbidden.test(line) || (isApi && forbiddenApi.test(line))) {
          offenders.push(`${relative(project, full)}:${i + 1}: ${line.trim()}`);
        }
      });
  }
};

scan(join(project, 'src'));
scan(join(project, 'scripts'), { skip: ['check-no-sw.mjs'] });

for (const file of ['angular.json', 'package.json', 'ngsw-config.json']) {
  const full = join(project, file);
  if (file === 'ngsw-config.json' && existsSync(full)) {
    offenders.push(`${file}: service worker configuration must not exist`);
    continue;
  }
  if (!existsSync(full)) continue;
  readFileSync(full, 'utf8')
    .split('\n')
    .forEach((line, i) => {
      if (forbidden.test(line)) offenders.push(`${file}:${i + 1}: ${line.trim()}`);
    });
}

if (offenders.length) {
  console.error(
    'Service worker or client-side API cache detected. The cache policy is owned by the edge:\n' +
      'hashed assets immutable, index.html/version.json no-store, /api/v1 no-store.\n' +
      offenders.join('\n'),
  );
  process.exit(1);
}

console.log('check:no-sw OK');
