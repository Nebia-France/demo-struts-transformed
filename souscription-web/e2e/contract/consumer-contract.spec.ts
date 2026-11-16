import { expect, test } from '@playwright/test';
import type { BrowserContext, Page, Request } from '@playwright/test';
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { FakeGateway } from '../support/fake-gateway';
import {
  OPERATIONS,
  countMonetaryFields,
  nonStringMonetaryFields,
  schemaErrors,
} from './contract-schema';

/**
 * Consumer contract of `souscription-web-to-souscription-gateway` (plan 45, `npm run contract:consumer`).
 *
 * One test per consumed operation: the call is issued from a document of the SPA origin, exactly as
 * the SPA issues it (same-origin `fetch`, double-submit `XSRF-TOKEN` cookie echoed in
 * `X-CSRF-Token`), and the payload is validated with Ajv against the response schema the contract
 * declares for the returned status. Every monetary field must arrive as a JSON string.
 *
 * Target selection:
 * - `CONTRACT_BASE_URL` set (e.g. `https://staging.horizon-souscription.example.com`): the origin
 *   serving the SPA and the deployed `souscription-gateway` `/api/v1` behind the shared ingress.
 *   Nothing is intercepted: the provider itself answers.
 * - unset: the production build served by `scripts/perf-server.mjs`, with every `/api/v1` call
 *   answered by the contract-shaped gateway double of `e2e/support/fake-gateway.ts`. The run is
 *   annotated `target: gateway-double` and the report says so; it proves the consumer side and
 *   the double, not the provider.
 *
 * The resilience and security assertions of `contracts/resilience-contract.json` and
 * `contracts/security-policy.json` run against the same target. The measured values are written to
 * `e2e/reports/contract-consumer.json` and transcribed into `docs/CONTRACT_VALIDATION.md`.
 */

const ROOT = resolve(__dirname, '../..');
const LIVE_ORIGIN = process.env['CONTRACT_BASE_URL']?.replace(/\/$/, '');
const TARGET = LIVE_ORIGIN ? 'live-gateway' : 'gateway-double';
const REPORT = join(ROOT, 'e2e/reports/contract-consumer.json');
const resilience = JSON.parse(readFileSync(join(ROOT, 'contracts/resilience-contract.json'), 'utf8'));
const security = JSON.parse(readFileSync(join(ROOT, 'contracts/security-policy.json'), 'utf8'));

const CLIENT_TIMEOUT_MS = 10_000;
const P95_MS = 600;
const P99_MS = 1_500;
const LATENCY_SAMPLES = 60;

interface Exchange {
  status: number;
  headers: Record<string, string>;
  body: unknown;
  ms: number;
}

interface CallOptions {
  body?: unknown;
  /** Echo the double-submit token on a mutation (default true). */
  csrf?: boolean;
}

const report = {
  target: TARGET,
  origin: '',
  date: new Date().toISOString(),
  operations: {} as Record<string, { method: string; path: string; status: number; ms: number; monetaryFields: number }>,
  latency: {} as Record<string, number>,
  resilience: {} as Record<string, unknown>,
  security: {} as Record<string, unknown>,
  responseHeaderNames: new Set<string>(),
};

let context: BrowserContext;
let page: Page;
let origin = '';
const samples: number[] = [];

/** Issues `method path` from the SPA origin and records the exchange. */
async function call(method: string, path: string, options: CallOptions = {}): Promise<Exchange> {
  const exchange = await page.evaluate(
    async ({ method, url, body, csrf }) => {
      const token = /(?:^|;\s*)XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1];
      const headers: Record<string, string> = { Accept: 'application/json' };
      if (body !== undefined) headers['Content-Type'] = 'application/json';
      if (csrf && token && method !== 'GET') headers['X-CSRF-Token'] = decodeURIComponent(token);
      const started = performance.now();
      const response = await fetch(url, {
        method,
        headers,
        credentials: 'same-origin',
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await response.text();
      const ms = performance.now() - started;
      const all: Record<string, string> = {};
      response.headers.forEach((value, name) => (all[name.toLowerCase()] = value));
      return { status: response.status, headers: all, body: text ? JSON.parse(text) : null, ms };
    },
    { method, url: `/api/v1${path}`, body: options.body, csrf: options.csrf ?? true },
  );
  Object.keys(exchange.headers).forEach((name) => report.responseHeaderNames.add(name));
  samples.push(exchange.ms);
  return exchange;
}

/** Calls `operationId`, asserts `expected` status, the schema and the monetary strings. */
async function exercise(operationId: string, path: string, expected: number, body?: unknown): Promise<Exchange> {
  const declared = OPERATIONS[operationId];
  expect(declared, `${operationId} is declared by the contract`).toBeTruthy();
  const exchange = await call(declared.method, path, { body });
  expect(exchange.status, `${operationId} ${JSON.stringify(exchange.body)}`).toBe(expected);
  expect(schemaErrors(operationId, expected, exchange.body), `${operationId} body matches the contract`).toEqual([]);
  expect(nonStringMonetaryFields(exchange.body), `${operationId}: every monetary field is a string`).toEqual([]);
  report.operations[operationId] = {
    method: declared.method,
    path: declared.path,
    status: exchange.status,
    ms: Math.round(exchange.ms * 10) / 10,
    monetaryFields: countMonetaryFields(exchange.body),
  };
  return exchange;
}

function percentile(values: number[], p: number): number {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}

/** State carried from one operation to the next, as the wizard carries it. */
const journey = {
  productCode: '',
  draftId: '',
  confirmationKey: '',
  reference: '',
};

test.describe.configure({ mode: 'serial' });

test.beforeAll(async ({ browser, baseURL }) => {
  origin = LIVE_ORIGIN ?? String(baseURL);
  report.origin = origin;
  context = await browser.newContext({ ignoreHTTPSErrors: false });
  page = await context.newPage();
  if (!LIVE_ORIGIN) {
    await new FakeGateway().install(page, origin);
  }
  // A static document of the SPA origin: the calls below are same-origin, as the SPA's are.
  const response = await page.goto(`${origin}/version.json`);
  expect(response?.ok(), `${origin} serves the SPA`).toBe(true);
});

test.afterAll(async () => {
  mkdirSync(join(ROOT, 'e2e/reports'), { recursive: true });
  writeFileSync(
    REPORT,
    JSON.stringify({ ...report, responseHeaderNames: [...report.responseHeaderNames].sort() }, null, 2) + '\n',
  );
  await context?.close();
});

test.beforeEach(() => {
  test.info().annotations.push({ type: 'target', description: `${TARGET} (${origin})` });
});

test.describe('the 10 consumed operations', () => {
  test('issueCsrfToken: GET /csrf-token issues the double-submit token', async () => {
    const exchange = await exercise('issueCsrfToken', '/csrf-token', 200);
    const cookie = (await context.cookies(origin)).find((c) => c.name === 'XSRF-TOKEN');
    expect(cookie, 'XSRF-TOKEN cookie set').toBeTruthy();
    expect((exchange.body as { token: string }).token, 'body carries the cookie value').toBe(cookie!.value);
  });

  test('listProducts: GET /products', async () => {
    const exchange = await exercise('listProducts', '/products', 200);
    const products = exchange.body as { code: string }[];
    expect(products.length).toBeGreaterThan(0);
    journey.productCode = products[0].code;
  });

  test('getProduct: GET /products/{code}', async () => {
    const exchange = await exercise('getProduct', `/products/${encodeURIComponent(journey.productCode)}`, 200);
    expect((exchange.body as { code: string }).code).toBe(journey.productCode);
  });

  test('simulate: POST /simulations', async () => {
    const exchange = await exercise('simulate', '/simulations', 200, {
      productCode: journey.productCode,
      initialAmount: '1000.00',
      monthlyAmount: '100.00',
      durationYears: 5,
    });
    expect(countMonetaryFields(exchange.body)).toBe(4);
  });

  test('startDraft: POST /drafts', async () => {
    const exchange = await exercise('startDraft', '/drafts', 201, { productCode: journey.productCode });
    const draft = exchange.body as { draftId: string; status: string };
    expect(draft.status).toBe('STARTED');
    expect(exchange.headers['location']).toContain(draft.draftId);
    journey.draftId = draft.draftId;
  });

  test('getDraft: GET /drafts/{draftId}', async () => {
    const exchange = await exercise('getDraft', `/drafts/${journey.draftId}`, 200);
    expect((exchange.body as { draftId: string }).draftId).toBe(journey.draftId);
  });

  test('putDraftSubscriber: PUT /drafts/{draftId}/subscriber', async () => {
    const exchange = await exercise('putDraftSubscriber', `/drafts/${journey.draftId}/subscriber`, 200, {
      firstName: 'Camille',
      lastName: 'Durand',
      email: 'camille.durand@example.com',
      birthDate: '1980-05-14',
    });
    expect((exchange.body as { status: string }).status).toBe('SUBSCRIBER_CAPTURED');
  });

  test('putDraftSimulation: PUT /drafts/{draftId}/simulation', async () => {
    const exchange = await exercise('putDraftSimulation', `/drafts/${journey.draftId}/simulation`, 200, {
      initialAmount: '1000.00',
      monthlyAmount: '100.00',
      durationYears: 5,
    });
    const draft = exchange.body as { status: string; confirmationKey: string };
    expect(draft.status).toBe('SIMULATED');
    journey.confirmationKey = draft.confirmationKey;
  });

  test('confirmDraft: POST /drafts/{draftId}/confirmation, idempotent on the key', async () => {
    const body = { confirmationKey: journey.confirmationKey };
    const created = await exercise('confirmDraft', `/drafts/${journey.draftId}/confirmation`, 201, body);
    journey.reference = (created.body as { reference: string }).reference;
    expect(created.headers['location']).toContain(journey.reference);
    const replay = await call('POST', `/drafts/${journey.draftId}/confirmation`, { body });
    expect(replay.status, 'replay of the same key').toBe(200);
    expect(schemaErrors('confirmDraft', 200, replay.body)).toEqual([]);
    expect((replay.body as { reference: string }).reference, 'no second subscription').toBe(journey.reference);
  });

  test('getSubscription: GET /subscriptions/{reference}', async () => {
    const exchange = await exercise('getSubscription', `/subscriptions/${encodeURIComponent(journey.reference)}`, 200);
    expect((exchange.body as { reference: string }).reference).toBe(journey.reference);
    expect(Object.keys(report.operations).sort()).toEqual(Object.keys(OPERATIONS).sort());
  });
});

test.describe('security policy', () => {
  test('a mutation without X-CSRF-Token is refused with 403 problem+json', async () => {
    const refused = await call('POST', '/drafts', { body: { productCode: journey.productCode }, csrf: false });
    expect(refused.status).toBe(security.authentication.details.csrf.rejectionStatus);
    expect(refused.headers['content-type']).toContain('application/problem+json');
    expect(schemaErrors('startDraft', 403, refused.body)).toEqual([]);
    report.security['mutationWithoutTokenStatus'] = refused.status;
  });

  test('XSRF-TOKEN cookie: SameSite=Strict, readable by the SPA, Secure over HTTPS', async () => {
    const csrf = security.authentication.details.csrf;
    const cookie = (await context.cookies(origin)).find((c) => c.name === csrf.cookieName);
    expect(cookie).toBeTruthy();
    expect(cookie!.sameSite).toBe('Strict');
    expect(cookie!.httpOnly).toBe(false);
    if (origin.startsWith('https:')) expect(cookie!.secure).toBe(true);
    expect(csrf.headerName).toBe('X-CSRF-Token');
    expect(csrf.tokenIssuanceEndpoint).toBe('GET /api/v1/csrf-token');
    report.security['cookie'] = {
      name: cookie!.name,
      sameSite: cookie!.sameSite,
      httpOnly: cookie!.httpOnly,
      secure: cookie!.secure,
      path: cookie!.path,
    };
    report.security['headerName'] = csrf.headerName;
  });

  test('no cross-origin header on any gateway response (same origin)', async () => {
    const crossOrigin = [...report.responseHeaderNames].filter((name) => /^access-control-/i.test(name));
    expect(crossOrigin).toEqual([]);
    expect(security.network.cors.required).toBe(false);
    report.security['crossOriginHeaders'] = crossOrigin.length;
  });
});

test.describe('resilience contract', () => {
  test(`latency over ${LATENCY_SAMPLES} reads: p95 <= ${P95_MS} ms, p99 <= ${P99_MS} ms`, async () => {
    for (let i = 0; i < LATENCY_SAMPLES; i++) {
      const exchange = await call('GET', i % 2 ? '/products' : `/products/${journey.productCode}`);
      expect(exchange.status).toBe(200);
    }
    const p50 = percentile(samples, 50);
    const p95 = percentile(samples, 95);
    const p99 = percentile(samples, 99);
    report.latency = {
      samples: samples.length,
      p50: Math.round(p50 * 10) / 10,
      p95: Math.round(p95 * 10) / 10,
      p99: Math.round(p99 * 10) / 10,
      max: Math.round(Math.max(...samples) * 10) / 10,
    };
    expect(resilience.sla.responseTime.p95).toBe(`${P95_MS}ms`);
    expect(resilience.sla.responseTime.p99).toBe(`${P99_MS}ms`);
    expect(p95).toBeLessThanOrEqual(P95_MS);
    expect(p99).toBeLessThanOrEqual(P99_MS);
  });

  test('the provider serves 6 concurrent calls, the bulkhead size', async () => {
    const limit = resilience.bulkhead.maxConcurrentCalls as number;
    expect(limit).toBe(6);
    const statuses = await page.evaluate(
      async ({ n, code }) =>
        Promise.all(
          Array.from({ length: n }, () =>
            fetch(`/api/v1/products/${code}`, { credentials: 'same-origin' }).then((r) => r.status),
          ),
        ),
      { n: limit, code: journey.productCode },
    );
    expect(statuses).toEqual(Array(limit).fill(200));
    report.resilience['concurrentCallsServed'] = limit;
  });

  test('the production SPA aborts a slow call at 10 s, retries 0 times, keeps <= 6 in flight', async ({ browser, baseURL }) => {
    test.setTimeout(60_000);
    expect(resilience.timeout.value).toBe('10s');
    expect(resilience.retry.maxRetries).toBe(0);
    const spaOrigin = LIVE_ORIGIN ?? String(baseURL);
    const spaContext = await browser.newContext();
    const spa = await spaContext.newPage();
    if (!LIVE_ORIGIN) await new FakeGateway().install(spa, spaOrigin);
    const accessLog: string[] = [];
    let started: number | undefined;
    let failedAt: number | undefined;
    let inFlight = 0;
    let maxInFlight = 0;
    const isApi = (request: Request) => new URL(request.url()).pathname.startsWith('/api/v1/');
    const isProducts = (request: Request) => new URL(request.url()).pathname === '/api/v1/products';
    // Client-side fault injection in front of the target: the response is held 12 s.
    await spa.route('**/api/v1/products', async (route) => {
      accessLog.push(route.request().method());
      await new Promise((r) => setTimeout(r, 12_000));
      await route.fallback().catch(() => undefined);
    });
    spa.on('request', (request) => {
      if (!isApi(request)) return;
      maxInFlight = Math.max(maxInFlight, ++inFlight);
      if (isProducts(request) && started === undefined) started = Date.now();
    });
    const done = (request: Request) => isApi(request) && inFlight--;
    spa.on('requestfinished', done);
    spa.on('requestfailed', (request) => {
      done(request);
      if (isProducts(request) && failedAt === undefined) failedAt = Date.now();
    });
    await spa.goto(`${spaOrigin}/catalogue`, { waitUntil: 'commit' });
    await expect.poll(() => failedAt, { timeout: CLIENT_TIMEOUT_MS + 10_000 }).toBeDefined();
    await spa.waitForTimeout(2_000); // room for a retry that must not come
    const elapsed = failedAt! - started!;
    report.resilience['clientTimeoutMs'] = elapsed;
    report.resilience['attempts'] = accessLog.length;
    report.resilience['retries'] = accessLog.length - 1;
    report.resilience['maxInFlight'] = maxInFlight;
    await spaContext.close();
    expect(elapsed).toBeGreaterThanOrEqual(CLIENT_TIMEOUT_MS - 250);
    expect(elapsed).toBeLessThanOrEqual(CLIENT_TIMEOUT_MS + 2_000);
    expect(accessLog).toEqual(['GET']);
    expect(maxInFlight).toBeLessThanOrEqual(6);
  });
});

test.describe('gateway stub switch', () => {
  test('production configuration sets NG_APP_USE_GATEWAY_STUB to false and ships no msw', async () => {
    const angular = JSON.parse(readFileSync(join(ROOT, 'angular.json'), 'utf8'));
    const production = angular.projects['souscription-web'].architect.build.configurations.production;
    expect(production.define['import.meta.env.NG_APP_USE_GATEWAY_STUB']).toBe('"false"');
    const dist = join(ROOT, 'dist/souscription-web/browser');
    expect(existsSync(join(dist, 'index.html')), 'run npm run build first').toBe(true);
    const offenders: string[] = [];
    const walk = (dir: string) => {
      for (const entry of readdirSync(dir)) {
        const full = join(dir, entry);
        if (statSync(full).isDirectory()) walk(full);
        else if (entry.includes('msw') || entry === 'mockServiceWorker.js' || readFileSync(full).includes('msw')) {
          offenders.push(full);
        }
      }
    };
    walk(dist);
    expect(offenders).toEqual([]);
    report.resilience['stubInProductionBundle'] = offenders.length > 0;
  });
});
