/**
 * Fails the build if any CORS configuration appears in `deploy/` or `src/`.
 *
 * Decision OD-004: the SPA and souscription-gateway are served from one origin behind the
 * shared ingress, so no cross-origin exchange exists and no `Access-Control` header must
 * ever be emitted or expected. A CORS header appearing here would mean the origin model has
 * silently changed and that the `SameSite=Strict` CSRF cookie of
 * contracts/security-policy.json no longer protects the mutating calls.
 *
 * Only actual configuration is matched, not the word "CORS" in prose: the error-classification
 * comments legitimately mention CORS as a cause of a status-0 transport failure.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');

const FORBIDDEN = [
  // Matched case-insensitively from a lower-cased literal so that the repository itself holds
  // no occurrence of the header name in its canonical casing.
  /access-control-/i,
  /\benable-cors\b/i,
  /\bcors-allow-/i,
  /\bcrossOrigin\b/,
  /@CrossOrigin\b/,
  /\bcors\s*\(/i,
  /\ballow-origin\b/i,
];

const offenders = [];

/**
 * `deploy/smoke-headers.sh` greps for a lower-cased `access-control-` to assert the header is
 * *absent* from the deployed responses; it is the enforcement, not a violation.
 */
const SKIP = new Set(['smoke-headers.sh']);

const scan = (dir) => {
  for (const entry of readdirSync(dir)) {
    if (SKIP.has(entry)) continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      scan(full);
      continue;
    }
    if (!/\.(ts|html|scss|json|yaml|yml|conf|sh|mjs|js)$/.test(entry)) continue;
    readFileSync(full, 'utf8')
      .split('\n')
      .forEach((line, i) => {
        if (FORBIDDEN.some((pattern) => pattern.test(line))) {
          offenders.push(`${relative(root, full)}:${i + 1}: ${line.trim()}`);
        }
      });
  }
};

scan(join(root, 'src'));
scan(join(root, 'deploy'));

if (offenders.length) {
  console.error(
    'CORS configuration detected. The SPA and the gateway share one origin (OD-004):\n' +
      'no Access-Control header is part of this deployment.\n' +
      offenders.join('\n'),
  );
  process.exit(1);
}

console.log('check:no-cors OK');
