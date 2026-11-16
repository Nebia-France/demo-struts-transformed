/**
 * W7-T04 guard: fails the build if a telemetry payload builder could carry subscriber data.
 *
 * The source tier kept the subscriber's identity server-side; the client-side signal added by
 * plan 36 must not become the leak that tier never had. No file under
 * `src/app/core/telemetry` may name an identity field, the CSRF token, or serialize a whole
 * request body, so the privacy invariant is checked statically instead of being left to review.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../src/app/core/telemetry');

/** Field names the payloads may never reference, from the privacy constraint of plan 36. */
const FORBIDDEN_IDENTIFIERS = ['firstName', 'lastName', 'email', 'birthDate', 'XSRF', 'X-CSRF-Token'];

/** Ways a whole request/response body ends up in a payload. */
const BODY_PATTERNS = [
  /\breq(?:uest)?\.body\b/,
  /\bres(?:ponse)?\.body\b/,
  /\bbody\s*:\s*(?!JSON\.stringify\(payload\))/,
  /JSON\.stringify\(\s*(?:body|request|response|form|draft|subscriber)\b/,
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
    const lines = readFileSync(full, 'utf8').split('\n');
    lines.forEach((line, index) => {
      for (const identifier of FORBIDDEN_IDENTIFIERS) {
        if (line.includes(identifier)) {
          offenders.push(`${full}:${index + 1}: references ${identifier}`);
        }
      }
      // The body patterns describe a payload builder; a spec legitimately names a captured
      // `body` to assert on it, so only the identifier ban applies there.
      if (full.endsWith('.spec.ts')) return;
      for (const pattern of BODY_PATTERNS) {
        if (pattern.test(line)) {
          offenders.push(`${full}:${index + 1}: sends a request/response body: ${line.trim()}`);
        }
      }
    });
  }
};
walk(root);

if (offenders.length) {
  console.error(
    'A telemetry payload builder could carry personal data or the CSRF token.\n' +
      offenders.join('\n'),
  );
  process.exit(1);
}

console.log('check:privacy OK');
