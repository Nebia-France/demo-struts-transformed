/**
 * Fails the build when the double-submit CSRF wiring drifts from
 * contracts/security-policy.json. Three guards:
 *
 *   1. `XSRF_CONFIG` in src/app/app.config.ts declares exactly the contract cookie name
 *      (`XSRF-TOKEN`) and header name (`X-CSRF-Token`).
 *   2. No hand-rolled CSRF interceptor remains: Angular's built-in XSRF interceptor of
 *      `provideHttpClient` is the only implementation of the scheme.
 *   3. The token never leaks: neither name may appear in a `console.*` call nor in a
 *      template interpolation `{{ ... }}`.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const policyPath = join(root, 'contracts/security-policy.json');
const configPath = join(root, 'src/app/app.config.ts');

const policy = JSON.parse(readFileSync(policyPath, 'utf8'));
const { cookieName, headerName } = policy.authentication.details.csrf;
const config = readFileSync(configPath, 'utf8');

const errors = [];

if (!cookieName || !headerName) {
  errors.push(`${policyPath} declares no csrf cookieName/headerName`);
}

const declared = /XSRF_CONFIG\s*=\s*\{([^}]*)\}/.exec(config);
if (!declared) {
  errors.push(`no XSRF_CONFIG declaration found in ${configPath}`);
} else {
  const cookie = /cookieName:\s*'([^']*)'/.exec(declared[1]);
  const header = /headerName:\s*'([^']*)'/.exec(declared[1]);
  if (cookie?.[1] !== cookieName) {
    errors.push(`cookieName is "${cookie?.[1] ?? '(absent)'}", the contract requires "${cookieName}"`);
  }
  if (header?.[1] !== headerName) {
    errors.push(`headerName is "${header?.[1] ?? '(absent)'}", the contract requires "${headerName}"`);
  }
}

// Guard 2b: the built-in interceptor must actually be wired with the contract names. Without
// `withXsrfConfiguration(XSRF_CONFIG)` no header is attached at all and every mutation is
// rejected 403.
const providersPath = join(root, 'src/app/core/http.providers.ts');
const providers = readFileSync(providersPath, 'utf8');
if (!/withXsrfConfiguration\(\s*XSRF_CONFIG\s*\)/.test(providers)) {
  errors.push(`${providersPath} does not wire withXsrfConfiguration(XSRF_CONFIG) into provideHttpClient`);
}

/** Every source file of the SPA, templates included. */
function sourceFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      out.push(...sourceFiles(path));
    } else if (/\.(ts|html)$/.test(entry)) {
      out.push(path);
    }
  }
  return out;
}

const names = [cookieName, headerName].filter(Boolean);
for (const file of sourceFiles(join(root, 'src'))) {
  const content = readFileSync(file, 'utf8');
  const relative = file.slice(root.length + 1);
  if (/csrfInterceptor|csrf\.interceptor/.test(content)) {
    errors.push(`${relative} references a custom CSRF interceptor; the built-in XSRF interceptor must be used`);
  }
  content.split(/\r?\n/).forEach((line, index) => {
    const location = `${relative}:${index + 1}`;
    for (const name of names) {
      if (!line.includes(name)) {
        continue;
      }
      if (/console\s*\.\s*\w+\s*\(/.test(line)) {
        errors.push(`${location} logs the CSRF token name in a console call`);
      }
      if (/\{\{[^}]*\}\}/.test(line)) {
        errors.push(`${location} exposes the CSRF token in a template interpolation`);
      }
    }
  });
}

if (errors.length) {
  console.error(
    'The CSRF double-submit wiring does not match contracts/security-policy.json.\n' +
      errors.map((e) => `  - ${e}`).join('\n'),
  );
  process.exit(1);
}

console.log('check:csrf OK');
