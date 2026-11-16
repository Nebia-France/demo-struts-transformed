import type { Page, Route } from '@playwright/test';

/**
 * Gateway stub of the five journey specs (W8-T01).
 *
 * The journeys run against the production build served by `scripts/perf-server.mjs`, which drops
 * msw, so every `/api/v1` call is answered here through `page.route`. Each spec overrides only the
 * operation it exercises, which keeps the fault injections (403, 410, 503, held confirmation)
 * local to the scenario instead of hidden in a shared mock server.
 *
 * Bodies are contract-shaped: amounts and the annual rate are decimal strings, dates ISO-8601.
 */

export const PRODUCT = {
  code: 'PRUDENT',
  name: 'Horizon Prudent',
  profile: 'Prudent',
  description: 'Support securise, volatilite faible.',
  annualRate: '0.020000',
};

export const PRODUCT_SUMMARY = {
  code: PRODUCT.code,
  name: PRODUCT.name,
  profile: PRODUCT.profile,
  annualRate: PRODUCT.annualRate,
};

/** Double-submit token of `issueCsrfToken`; the contract body requires at least 32 characters. */
export const JOURNEY_CSRF_TOKEN = 'journey-csrf-token-0123456789abcdef';

export const DRAFT_ID = '7f1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d';
export const CONFIRMATION_KEY = '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d';
export const REFERENCE = 'SUB-2024-000001';

export const SUBSCRIBER = {
  firstName: 'Camille',
  lastName: 'Durand',
  email: 'camille.durand@example.com',
  birthDate: '1985-03-14',
};

export const SIMULATION = {
  initialAmount: '1000.00',
  monthlyAmount: '100.00',
  durationYears: 5,
  contributed: '7000.00',
  finalCapital: '7408.11',
};

const draft = (
  status: 'STARTED' | 'SUBSCRIBER_CAPTURED' | 'SIMULATED' | 'CONFIRMED',
  extra: Record<string, unknown> = {},
) => ({
  draftId: DRAFT_ID,
  product: PRODUCT,
  subscriber: null,
  simulation: null,
  confirmationKey: CONFIRMATION_KEY,
  status,
  ...extra,
});

export const STARTED_DRAFT = draft('STARTED');
export const SUBSCRIBER_DRAFT = draft('SUBSCRIBER_CAPTURED', { subscriber: SUBSCRIBER });
export const SIMULATED_DRAFT = draft('SIMULATED', {
  subscriber: SUBSCRIBER,
  simulation: SIMULATION,
});

export const CONFIRMATION = {
  reference: REFERENCE,
  product: PRODUCT_SUMMARY,
  subscriber: SUBSCRIBER,
  simulation: SIMULATION,
  createdAt: '2024-05-01T09:30:00Z',
};

/** Operations of `contracts/openapi.yaml` the SPA consumes. */
export type GatewayOperation =
  | 'issueCsrfToken'
  | 'listProducts'
  | 'getProduct'
  | 'startDraft'
  | 'getDraft'
  | 'putSubscriber'
  | 'putSimulation'
  | 'simulate'
  | 'confirmDraft';

export type GatewayHandler = (route: Route) => Promise<void> | void;

export type GatewayOverrides = Partial<Record<GatewayOperation, GatewayHandler>>;

/** Fulfils `route` with a JSON body, as the gateway does. */
export async function json(route: Route, status: number, body: unknown): Promise<void> {
  await route.fulfill({
    status,
    contentType: status >= 400 ? 'application/problem+json' : 'application/json',
    body: JSON.stringify(body),
  });
}

/** Fulfils `route` with an RFC 7807 problem document of `status`. */
export async function problem(route: Route, status: number, title: string): Promise<void> {
  await json(route, status, { type: 'about:blank', title, status });
}

function operationOf(method: string, pathname: string): GatewayOperation | null {
  if (method === 'GET' && pathname === '/api/v1/csrf-token') return 'issueCsrfToken';
  if (method === 'GET' && pathname === '/api/v1/products') return 'listProducts';
  if (method === 'GET' && pathname.startsWith('/api/v1/products/')) return 'getProduct';
  if (method === 'POST' && pathname === '/api/v1/drafts') return 'startDraft';
  if (method === 'POST' && pathname === '/api/v1/simulations') return 'simulate';
  if (method === 'PUT' && pathname.endsWith('/subscriber')) return 'putSubscriber';
  if (method === 'PUT' && pathname.endsWith('/simulation')) return 'putSimulation';
  if (method === 'POST' && pathname.endsWith('/confirmation')) return 'confirmDraft';
  if (method === 'GET' && pathname.startsWith('/api/v1/drafts/')) return 'getDraft';
  return null;
}

const defaults: Record<GatewayOperation, GatewayHandler> = {
  // The SPA never reads the body: only the double-submit cookie matters.
  issueCsrfToken: async (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Set-Cookie': `XSRF-TOKEN=${JOURNEY_CSRF_TOKEN}; Path=/; SameSite=Strict` },
      body: JSON.stringify({ token: JOURNEY_CSRF_TOKEN }),
    }),
  listProducts: (route) => json(route, 200, [PRODUCT_SUMMARY]),
  getProduct: (route) => json(route, 200, PRODUCT),
  startDraft: (route) => json(route, 201, STARTED_DRAFT),
  getDraft: (route) => json(route, 200, SIMULATED_DRAFT),
  putSubscriber: (route) => json(route, 200, SUBSCRIBER_DRAFT),
  putSimulation: (route) => json(route, 200, SIMULATED_DRAFT),
  simulate: (route) => json(route, 200, SIMULATION),
  confirmDraft: (route) => json(route, 201, CONFIRMATION),
};

/** Installs the gateway stub on `page`; `overrides` replaces single operations. */
export async function stubGateway(page: Page, overrides: GatewayOverrides = {}): Promise<void> {
  // The double-submit cookie the gateway issues on `GET /csrf-token`: Angular's built-in XSRF
  // interceptor reads it to set `X-CSRF-Token` on every mutating call.
  const { origin } = new URL(process.env['JOURNEYS_BASE_URL'] ?? 'http://localhost:4300');
  await page.context().addCookies([
    { name: 'XSRF-TOKEN', value: JOURNEY_CSRF_TOKEN, url: origin },
  ]);
  await page.route('**/api/v1/**', async (route) => {
    const { pathname } = new URL(route.request().url());
    const operation = operationOf(route.request().method(), pathname);
    if (operation === null) {
      await problem(route, 404, 'Not Found');
      return;
    }
    await (overrides[operation] ?? defaults[operation])(route);
  });
}

/** Walks the journey from the catalogue to the filled récapitulatif, acceptance ticked. */
export async function goToSummary(page: Page): Promise<void> {
  await page.goto('/catalogue');
  await page.getByRole('link', { name: PRODUCT.name, exact: true }).click();
  await page.getByRole('button', { name: 'Commencer la souscription >' }).click();

  await page.locator('input#firstName').fill(SUBSCRIBER.firstName);
  await page.locator('input#lastName').fill(SUBSCRIBER.lastName);
  await page.locator('input#email').fill(SUBSCRIBER.email);
  await page.locator('input#birthDate').fill('14/03/1985');
  await page.getByRole('button', { name: 'Continuer >' }).click();

  await page.locator('input#initial').waitFor();
  await page.getByRole('button', { name: 'Continuer >' }).click();

  await page.locator('input#accepted').check();
}
