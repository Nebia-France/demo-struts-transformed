/**
 * Fails the build if the Content-Security-Policy served by deploy/nginx.conf is weaker than
 * the baseline of src/main/java/fr/demo/souscription/web/SecurityFilter.java.
 *
 * Baseline (source): default-src 'self'; style-src 'self'; img-src 'self'; script-src 'none';
 *                    object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'
 * Target: identical, except script-src 'self' (a SPA executes its own hashed bundles) and the
 * added connect-src 'self'. No unsafe-inline, no unsafe-eval, no wildcard source.
 *
 * `style-src` additionally admits a per-response nonce (`'nonce-$request_id'`): Angular inserts
 * component stylesheets as <style> elements and stamps them with the `ngCspNonce` of <app-root>,
 * which nginx substitutes per response (`sub_filter`). The guard also fails when that wiring is
 * incomplete, and when src/index.html carries a `<base>` element, which `base-uri 'none'` voids.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const NONCE_PLACEHOLDER = '__CSP_NONCE__';
const indexHtml = readFileSync(resolve(import.meta.dirname, '../src/index.html'), 'utf8');

const nginxConf = resolve(import.meta.dirname, '../deploy/nginx.conf');
const conf = readFileSync(nginxConf, 'utf8');

const required = {
  'default-src': "'self'",
  'script-src': "'self'",
  'style-src': "'self' 'nonce-$request_id'",
  'img-src': "'self'",
  'connect-src': "'self'",
  'object-src': "'none'",
  'base-uri': "'none'",
  'frame-ancestors': "'none'",
  'form-action': "'self'",
};

const errors = [];

const match = /add_header\s+Content-Security-Policy\s+"([^"]*)"/.exec(conf);
if (!match) {
  errors.push(`no add_header Content-Security-Policy found in ${nginxConf}`);
} else {
  const csp = match[1];
  const directives = new Map(
    csp
      .split(';')
      .map((part) => part.trim())
      .filter(Boolean)
      .map((part) => {
        const [name, ...values] = part.split(/\s+/);
        return [name, values.join(' ')];
      }),
  );

  for (const [name, value] of Object.entries(required)) {
    if (!directives.has(name)) {
      errors.push(`missing directive "${name} ${value}"`);
    } else if (directives.get(name) !== value) {
      errors.push(`directive "${name}" is "${directives.get(name)}", expected "${value}"`);
    }
  }

  if (/unsafe-inline/i.test(csp)) errors.push("'unsafe-inline' is forbidden");
  if (/unsafe-eval/i.test(csp)) errors.push("'unsafe-eval' is forbidden");
  if (/(^|[\s'"])\*/.test(csp)) errors.push('a wildcard source is forbidden');
}

if (!new RegExp(`sub_filter\\s+'${NONCE_PLACEHOLDER}'\\s+'\\$request_id'`).test(conf)) {
  errors.push(`no "sub_filter '${NONCE_PLACEHOLDER}' '$request_id'" writing the CSP nonce into index.html`);
}
if (!indexHtml.includes(`ngCspNonce="${NONCE_PLACEHOLDER}"`)) {
  errors.push(`src/index.html: <app-root> must carry ngCspNonce="${NONCE_PLACEHOLDER}"`);
}
if (/<base[\s>]/i.test(indexHtml)) {
  errors.push("src/index.html: a <base> element is ignored under base-uri 'none' (use APP_BASE_HREF)");
}

for (const header of [
  ['X-Content-Type-Options', 'nosniff'],
  ['X-Frame-Options', 'DENY'],
  ['Referrer-Policy', 'no-referrer'],
  ['Strict-Transport-Security', 'max-age=31536000; includeSubDomains'],
]) {
  const [name, value] = header;
  const found = new RegExp(`add_header\\s+${name}\\s+"([^"]*)"`, 'i').exec(conf);
  if (!found) {
    errors.push(`missing add_header ${name}`);
  } else if (found[1] !== value) {
    errors.push(`${name} is "${found[1]}", expected "${value}"`);
  }
}

if (errors.length) {
  console.error(
    'The served security policy is weaker than the SecurityFilter.java baseline.\n' +
      errors.map((e) => `  - ${e}`).join('\n'),
  );
  process.exit(1);
}

console.log('check:csp OK');
