import { expect, test } from '@playwright/test';
import type { APIRequestContext } from '@playwright/test';

/**
 * Negative API tests that bypass the UI entirely (W6-T05).
 *
 * The client validators of `src/app/wizard/form-models.ts` are UX only: the authoritative rules
 * live in `src/main/java/fr/demo/souscription/service/InputValidator.java`, owned by the backend
 * applications. These tests therefore talk to the gateway directly — no page, no form — so a
 * refusal proves the server rejects the value and not that the browser blocked the submission.
 *
 * One case per `InputValidator` rule: initial amount 100–100 000 €, monthly 0–2 000 €, duration
 * 1–30 years, legal age 18, lengths 80/80/254, control characters refused. Each asserts HTTP 400
 * with the matching `FieldErrors` key.
 *
 * Kept out of the default gate (`npm run api:negative`): it needs a running gateway. It is
 * promoted to a blocking job in plans 40 and 43.
 */

const API_BASE_URL = (process.env['API_BASE_URL'] ?? 'http://localhost:4201/api/v1').replace(
  /\/$/,
  '',
);
/** Product code of the catalogue used to open the draft under test. */
const PRODUCT_CODE = process.env['API_PRODUCT_CODE'] ?? 'PRUDENT';

/** A valid subscriber, mutated field by field by the subscriber cases. */
const VALID_SUBSCRIBER = {
  firstName: 'Camille',
  lastName: 'Durand',
  email: 'camille.durand@example.com',
  birthDate: '1980-05-14',
};

/** A valid simulation input, mutated field by field by the simulation cases. */
const VALID_SIMULATION = {
  initialAmount: '1000.00',
  monthlyAmount: '100.00',
  durationYears: 5,
};

/** ISO date of the subscriber turning 18 tomorrow: one day short of the legal age. */
function justUnderEighteen(): string {
  const date = new Date();
  date.setUTCFullYear(date.getUTCFullYear() - 18);
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}

/** ISO date one day in the future. */
function tomorrow(): string {
  const date = new Date();
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}

/** Double-submit CSRF pair of contracts/security-policy.json, required on PUT and POST. */
async function csrfHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const response = await request.get(`${API_BASE_URL}/csrf-token`);
  expect(response.status(), 'GET /csrf-token must issue a token').toBe(200);
  const setCookie = response.headers()['set-cookie'] ?? '';
  const token = /(?:^|[,;\s])XSRF-TOKEN=([^;,\s]+)/.exec(setCookie)?.[1];
  expect(token, 'GET /csrf-token must set the XSRF-TOKEN cookie').toBeTruthy();
  return { 'X-CSRF-Token': token as string, Cookie: `XSRF-TOKEN=${token}` };
}

/** Opens a draft and returns its id, so the subscriber and simulation rules can be exercised. */
async function openDraft(request: APIRequestContext): Promise<string> {
  const headers = await csrfHeaders(request);
  const response = await request.post(`${API_BASE_URL}/drafts`, {
    headers,
    data: { productCode: PRODUCT_CODE },
  });
  expect(response.status(), 'POST /drafts must open a draft').toBe(201);
  const draft = (await response.json()) as { draftId: string };
  return draft.draftId;
}

/** Asserts a 400 problem+json carrying a field error on `field`. */
async function expectFieldRejection(
  request: APIRequestContext,
  path: string,
  method: 'put' | 'post',
  data: unknown,
  field: string,
): Promise<void> {
  const headers = await csrfHeaders(request);
  const response = await request[method](`${API_BASE_URL}${path}`, { headers, data });
  expect(response.status(), `${method.toUpperCase()} ${path} must be refused`).toBe(400);
  const problem = (await response.json()) as { errors?: { field: string }[] };
  expect(
    (problem.errors ?? []).map((entry) => entry.field),
    `the FieldErrors document must name "${field}"`,
  ).toContain(field);
}

/**
 * Probes the gateway so the suite can be a blocking CI step (plan 38) before the gateway is
 * deployed (plans 40 and 43): when nothing answers `API_BASE_URL` the cases are skipped with an
 * explicit message instead of failing on the infrastructure. No case is removed or disabled; as
 * soon as a gateway answers, every rule below is enforced.
 */
async function gatewayAnswers(request: APIRequestContext): Promise<boolean> {
  try {
    const response = await request.get(`${API_BASE_URL}/csrf-token`, { timeout: 5_000 });
    // A static server or a dev server answers the SPA shell with 200 text/html; only a JSON
    // body proves a real gateway is behind API_BASE_URL.
    return (
      response.status() === 200 &&
      (response.headers()['content-type'] ?? '').includes('application/json')
    );
  } catch {
    return false;
  }
}

test.describe('InputValidator rules are enforced server-side, UI bypassed', () => {
  let draftId: string;

  test.beforeAll(async ({ request }) => {
    test.skip(
      !(await gatewayAnswers(request)),
      `No gateway answers ${API_BASE_URL}: the negative API suite needs a deployed souscription-gateway.`,
    );
    draftId = await openDraft(request);
  });

  // --- simulationErrors: initial amount between 100,00 and 100 000,00 -----------------------
  for (const [label, initialAmount] of [
    ['below the 100 € floor', '99.99'],
    ['above the 100 000 € ceiling', '100000.01'],
    ['not a decimal amount', 'mille'],
  ] as const) {
    test(`initialAmount refused: ${label}`, async ({ request }) => {
      await expectFieldRejection(
        request,
        `/drafts/${draftId}/simulation`,
        'put',
        { ...VALID_SIMULATION, initialAmount },
        'initialAmount',
      );
    });
  }

  // --- simulationErrors: monthly amount between 0,00 and 2 000,00 ---------------------------
  for (const [label, monthlyAmount] of [
    ['below the 0 € floor', '-1.00'],
    ['above the 2 000 € ceiling', '2000.01'],
    ['with more than two decimals', '100.123'],
  ] as const) {
    test(`monthlyAmount refused: ${label}`, async ({ request }) => {
      await expectFieldRejection(
        request,
        `/drafts/${draftId}/simulation`,
        'put',
        { ...VALID_SIMULATION, monthlyAmount },
        'monthlyAmount',
      );
    });
  }

  // --- simulationErrors: duration of 1 to 30 whole years ------------------------------------
  for (const [label, durationYears] of [
    ['below the 1 year floor', 0],
    ['above the 30 year ceiling', 31],
  ] as const) {
    test(`durationYears refused: ${label}`, async ({ request }) => {
      await expectFieldRejection(
        request,
        `/drafts/${draftId}/simulation`,
        'put',
        { ...VALID_SIMULATION, durationYears },
        'durationYears',
      );
    });
  }

  // --- subscriberErrors: first name 1 to 80 characters, no control character -----------------
  for (const [label, firstName] of [
    ['empty', '   '],
    ['longer than 80 characters', 'A'.repeat(81)],
    ['carrying a control character', 'Cami\u0007lle'],
  ] as const) {
    test(`firstName refused: ${label}`, async ({ request }) => {
      await expectFieldRejection(
        request,
        `/drafts/${draftId}/subscriber`,
        'put',
        { ...VALID_SUBSCRIBER, firstName },
        'firstName',
      );
    });
  }

  // --- subscriberErrors: last name 1 to 80 characters, no control character ------------------
  for (const [label, lastName] of [
    ['empty', ''],
    ['longer than 80 characters', 'B'.repeat(81)],
    ['carrying a control character', 'Dur\u0000and'],
  ] as const) {
    test(`lastName refused: ${label}`, async ({ request }) => {
      await expectFieldRejection(
        request,
        `/drafts/${draftId}/subscriber`,
        'put',
        { ...VALID_SUBSCRIBER, lastName },
        'lastName',
      );
    });
  }

  // --- subscriberErrors: email of 254 characters maximum, strict shape ----------------------
  for (const [label, email] of [
    ['longer than 254 characters', `${'c'.repeat(244)}@example.com`],
    ['without a domain', 'camille.durand@'],
    ['carrying angle brackets', 'camille<durand>@example.com'],
  ] as const) {
    test(`email refused: ${label}`, async ({ request }) => {
      await expectFieldRejection(
        request,
        `/drafts/${draftId}/subscriber`,
        'put',
        { ...VALID_SUBSCRIBER, email },
        'email',
      );
    });
  }

  // --- subscriberErrors: legal age of 18, no future date ------------------------------------
  for (const [label, birthDate] of [
    ['one day short of 18 years', justUnderEighteen()],
    ['in the future', tomorrow()],
    ['not a calendar date', '2001-02-30'],
  ] as const) {
    test(`birthDate refused: ${label}`, async ({ request }) => {
      await expectFieldRejection(
        request,
        `/drafts/${draftId}/subscriber`,
        'put',
        { ...VALID_SUBSCRIBER, birthDate },
        'birthDate',
      );
    });
  }
});
