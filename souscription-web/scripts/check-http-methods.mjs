/**
 * Guards the two controls that `SecurityFilter.java` used to enforce in-process and that
 * `TARGET_APPLICATION_TOPOLOGY.json` moves to souscription-gateway (`securityFilter` ->
 * `souscription-gateway`), plus the non-authoritative status of the client validators.
 *
 * Three guards:
 *
 *   1. The SPA issues only GET, POST and PUT. Any other verb in `src/app/api/*.service.ts`
 *      fails the build: the legacy filter answered 405 with `Allow: GET, HEAD, POST`, and the
 *      gateway allow-list is what replaces it. Adding a verb to the client without widening
 *      the contract would produce a runtime rejection instead of a build failure.
 *   2. The gateway allow-list of contracts/security-policy.json covers every verb the SPA
 *      issues and still refuses the verbs the legacy filter refused (PATCH, TRACE, CONNECT).
 *   3. Every client-side validator of src/app/wizard/form-models.ts has a counterpart in the
 *      server rule list below, transcribed from
 *      src/main/java/fr/demo/souscription/service/InputValidator.java. A browser-only rule
 *      would make the client authoritative, which it is not: the server revalidates every
 *      submission and its FieldErrors win.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');

/** Verbs the SPA is allowed to issue (operations of contracts/openapi.yaml). */
const CLIENT_METHODS = new Set(['get', 'post', 'put']);
/** Verbs the gateway must keep refusing, as the legacy SecurityFilter did. */
const MUST_REFUSE = ['PATCH', 'TRACE', 'CONNECT'];

/**
 * Server rules of `InputValidator`, keyed by the identifier each client validator mirrors.
 * A client validator with no entry here has no server counterpart and is rejected.
 */
const SERVER_RULES = {
  'Validators.required': 'InputValidator refuses an empty name, email, amount and duration',
  'Validators.maxLength(80)': 'InputValidator.validateName: 1 to 80 characters',
  'Validators.maxLength(254)': 'InputValidator.subscriberErrors: email of 254 characters maximum',
  frDatePattern: 'InputValidator.birthDate: strict dd/MM/uuuu parsing',
  frAmountValidator: 'InputValidator.amount: [0-9]{1,6}([.,][0-9]{1,2})?',
  frYearsValidator: 'InputValidator.simulationErrors: integer duration of 1 to 30 years',
};

const errors = [];

// --- 1. verbs issued by the SPA -------------------------------------------------------------
const apiDir = join(root, 'src/app/api');
const issued = new Set();
for (const entry of readdirSync(apiDir)) {
  if (!entry.endsWith('.service.ts')) continue;
  const lines = readFileSync(join(apiDir, entry), 'utf8').split('\n');
  lines.forEach((line, i) => {
    for (const match of line.matchAll(/\bhttp\.([a-zA-Z]+)\s*[<(]/g)) {
      const method = match[1];
      if (method === 'request') {
        errors.push(`${entry}:${i + 1}: HttpClient.request() hides the verb; call the typed method`);
        continue;
      }
      if (!CLIENT_METHODS.has(method)) {
        errors.push(
          `${entry}:${i + 1}: forbidden HTTP method "${method}" (allowed: ${[...CLIENT_METHODS].join(', ')})`,
        );
        continue;
      }
      issued.add(method.toUpperCase());
    }
  });
}
if (issued.size === 0) {
  errors.push('no HttpClient call found in src/app/api/*.service.ts; the scan is not working');
}

// --- 2. gateway allow-list ------------------------------------------------------------------
const policy = JSON.parse(readFileSync(join(root, 'contracts/security-policy.json'), 'utf8'));
const allowList = policy.authentication?.details?.methodAllowList;
if (!Array.isArray(allowList) || allowList.length === 0) {
  errors.push('contracts/security-policy.json declares no authentication.details.methodAllowList');
} else {
  for (const method of issued) {
    if (!allowList.includes(method)) {
      errors.push(`the gateway allow-list does not cover ${method}, which the SPA issues`);
    }
  }
  for (const method of MUST_REFUSE) {
    if (allowList.includes(method)) {
      errors.push(`the gateway allow-list accepts ${method}; the legacy SecurityFilter refused it`);
    }
  }
}

// --- 3. client validators are mirrors, never new rules --------------------------------------
const formModels = readFileSync(join(root, 'src/app/wizard/form-models.ts'), 'utf8');
const controls = [...formModels.matchAll(/\n\s*\w+: \[[^[]*\[([^\]]*)\]/g)];
// 4 subscriber controls + 3 simulation controls; a lower count means the scan missed a group.
if (controls.length < 7) {
  errors.push(
    `src/app/wizard/form-models.ts: only ${controls.length} validated controls found, expected 7`,
  );
}
for (const group of controls) {
  for (const raw of group[1].split(',')) {
    const rule = raw.trim();
    if (rule === '') continue;
    if (!(rule in SERVER_RULES)) {
      errors.push(
        `src/app/wizard/form-models.ts: client validator "${rule}" has no server counterpart; ` +
          'client validation is UX only (known rules: ' +
          `${Object.keys(SERVER_RULES).join(', ')})`,
      );
    }
  }
}

if (errors.length) {
  console.error(
    'HTTP method allow-list / client validation check failed.\n' +
      'The method allow-list and the UTF-8 encoding are owned by souscription-gateway;\n' +
      'the client validators mirror InputValidator and add nothing.\n' +
      errors.join('\n'),
  );
  process.exit(1);
}

console.log(`check:methods OK (SPA issues ${[...issued].sort().join(', ')})`);
