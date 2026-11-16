import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const published = resolve(
  root,
  '../../global/contracts/souscription-web-to-souscription-gateway/openapi.yaml',
);
const vendored = resolve(root, 'contracts/openapi.yaml');

const a = readFileSync(published, 'utf8');
const b = readFileSync(vendored, 'utf8');
if (a !== b) {
  console.error('Vendored contract diverges from the published gateway contract.');
  process.exit(1);
}

// The consumer contract validator (e2e/contract) compiles the vendored shared schemas the OpenAPI
// components point at, so they must be the published ones too.
const publishedSchemas = resolve(root, '../../global/contracts/shared-schemas');
const vendoredSchemas = resolve(root, 'shared-schemas');
const schemaFiles = readdirSync(publishedSchemas).filter((f) => f.endsWith('.json')).sort();
const vendoredFiles = readdirSync(vendoredSchemas).filter((f) => f.endsWith('.json')).sort();
if (schemaFiles.join() !== vendoredFiles.join()) {
  console.error('Vendored shared-schemas/ does not list the published shared schemas.');
  process.exit(1);
}
const divergent = schemaFiles.filter(
  (f) => readFileSync(join(publishedSchemas, f), 'utf8') !== readFileSync(join(vendoredSchemas, f), 'utf8'),
);
if (divergent.length) {
  console.error('Vendored shared schemas diverge from the published ones: ' + divergent.join(', '));
  process.exit(1);
}

const required = [
  'issueCsrfToken',
  'listProducts',
  'getProduct',
  'simulate',
  'startDraft',
  'getDraft',
  'putDraftSubscriber',
  'putDraftSimulation',
  'confirmDraft',
  'getSubscription',
];
const missing = required.filter((op) => !b.includes(`operationId: ${op}`));
if (missing.length) {
  console.error('Missing operations: ' + missing.join(', '));
  process.exit(1);
}

const strutsPath = /\/[a-z]+\.do\b/;
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
        if (strutsPath.test(line)) offenders.push(`${full}:${i + 1}: ${line.trim()}`);
      });
  }
};
walk(resolve(root, 'src'));
if (offenders.length) {
  console.error('Struts *.do URL found in src:\n' + offenders.join('\n'));
  process.exit(1);
}

console.log('contract:check OK');
