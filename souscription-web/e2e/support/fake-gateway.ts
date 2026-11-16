import { randomUUID } from 'node:crypto';
import type { BrowserContext, Page, Request, Route } from '@playwright/test';

/**
 * Stateful, contract-shaped test double of `souscription-gateway` for the behavioural gate
 * (`npm run e2e`, W8-T04).
 *
 * The cucumber-js steps drive the production SPA in Chromium; every `/api/v1` call the SPA issues
 * is answered here through `page.route`, so the SPA is asserted end to end without a deployed
 * backend. The double follows `contracts/openapi.yaml` (operations, statuses, RFC 7807 bodies,
 * decimal strings, ISO dates) and ports the legacy rules the backend applications own, so that the
 * SPA sees the answers the real chain gives:
 *
 * - catalogue reference data of `CatalogService` (souscription-catalogue-service);
 * - field rules of `InputValidator`, with an injectable clock (souscription-service);
 * - monthly capitalisation of `SimulationService` (souscription-simulation-service);
 * - `Draft` confirmation-key rotation and idempotent confirmation (souscription-service);
 * - double-submit CSRF check (`XSRF-TOKEN` cookie echoed as `X-CSRF-Token`, 100 characters at
 *   most) and 405 for any method the contract does not declare on a path (souscription-gateway).
 *
 * It is not a substitute for those applications' own acceptance suites: the rules are authoritative
 * there. Steps read its state directly to assert what the SPA sent.
 */

export interface ProductDetail {
  code: string;
  name: string;
  profile: string;
  description: string;
  annualRate: string;
}

export interface SubscriberInput {
  firstName: string;
  lastName: string;
  email: string;
  birthDate: string;
}

export interface SimulationResult {
  initialAmount: string;
  monthlyAmount: string;
  durationYears: number;
  contributed: string;
  finalCapital: string;
}

export interface DraftRecord {
  draftId: string;
  product: ProductDetail;
  subscriber: SubscriberInput | null;
  simulation: SimulationResult | null;
  confirmationKey: string;
  confirmed: boolean;
}

export interface SubscriptionRecord {
  reference: string;
  confirmationKey: string;
  draftId: string;
  product: ProductDetail;
  subscriber: SubscriberInput;
  simulation: SimulationResult;
  createdAt: string;
}

export type Operation =
  | 'issueCsrfToken'
  | 'listProducts'
  | 'getProduct'
  | 'simulate'
  | 'startDraft'
  | 'getDraft'
  | 'putDraftSubscriber'
  | 'putDraftSimulation'
  | 'confirmDraft'
  | 'getSubscription';

export interface RecordedCall {
  operation: Operation | null;
  method: string;
  path: string;
  status: number;
  requestHeaders: Record<string, string>;
  requestBody: unknown;
  responseBody: unknown;
}

/** Reference data of the legacy `CatalogService`. */
export const REFERENCE_PRODUCTS: readonly ProductDetail[] = Object.freeze([
  {
    code: 'PRUDENT',
    name: 'Horizon Prudent',
    profile: 'Prudent',
    description: "Une hypothèse de progression modérée pour découvrir le fonctionnement de l'épargne.",
    annualRate: '0.020000',
  },
  {
    code: 'EQUILIBRE',
    name: 'Horizon Équilibre',
    profile: 'Équilibré',
    description: 'Une hypothèse intermédiaire pour construire un projet dans la durée.',
    annualRate: '0.040000',
  },
  {
    code: 'DYNAMIQUE',
    name: 'Horizon Dynamique',
    profile: 'Dynamique',
    description: "Une hypothèse de progression plus élevée, associée à un profil de risque supérieur.",
    annualRate: '0.060000',
  },
]);

export const CSRF_COOKIE = 'XSRF-TOKEN';
export const CSRF_HEADER = 'x-csrf-token';
export const SIMULATION_REJECTED = 'Paramètres de simulation invalides';

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** Paths of the contract and the single method each declares. */
const ROUTES: { pattern: RegExp; method: string; operation: Operation }[] = [
  { pattern: /^\/csrf-token$/, method: 'GET', operation: 'issueCsrfToken' },
  { pattern: /^\/products$/, method: 'GET', operation: 'listProducts' },
  { pattern: /^\/products\/([^/]+)$/, method: 'GET', operation: 'getProduct' },
  { pattern: /^\/simulations$/, method: 'POST', operation: 'simulate' },
  { pattern: /^\/drafts$/, method: 'POST', operation: 'startDraft' },
  { pattern: /^\/drafts\/([^/]+)$/, method: 'GET', operation: 'getDraft' },
  { pattern: /^\/drafts\/([^/]+)\/subscriber$/, method: 'PUT', operation: 'putDraftSubscriber' },
  { pattern: /^\/drafts\/([^/]+)\/simulation$/, method: 'PUT', operation: 'putDraftSimulation' },
  { pattern: /^\/drafts\/([^/]+)\/confirmation$/, method: 'POST', operation: 'confirmDraft' },
  { pattern: /^\/subscriptions\/([^/]+)$/, method: 'GET', operation: 'getSubscription' },
];

interface Reply {
  status: number;
  body?: unknown;
  headers?: Record<string, string>;
  /** Double-submit token issued by this reply. */
  csrfToken?: string;
}

const problem = (status: number, title: string, errors?: FieldError[]): Reply => ({
  status,
  body: { type: 'about:blank', title, status, ...(errors ? { errors } : {}) },
});

interface FieldError {
  field: string;
  message: string;
  code: string;
}

export class FakeGateway {
  products: ProductDetail[] = REFERENCE_PRODUCTS.map((p) => ({ ...p }));
  /** Date of the server clock used by the majority rule, ISO `yyyy-MM-dd`. */
  today: string = new Date().toISOString().slice(0, 10);
  /** Fault injection: the subscription store answers 503 on confirmation. */
  confirmUnavailable = false;

  readonly drafts = new Map<string, DraftRecord>();
  readonly subscriptions: SubscriptionRecord[] = [];
  readonly calls: RecordedCall[] = [];

  private context?: BrowserContext;
  private origin = '';

  async install(page: Page, origin: string): Promise<void> {
    this.context = page.context();
    this.origin = origin;
    await page.route('**/api/v1/**', (route) => this.handle(route));
  }

  product(code: string): ProductDetail | undefined {
    return this.products.find((p) => p.code === code);
  }

  /** The draft the SPA works on: the most recently started one. */
  currentDraft(): DraftRecord | undefined {
    return [...this.drafts.values()].at(-1);
  }

  callsOf(operation: Operation): RecordedCall[] {
    return this.calls.filter((call) => call.operation === operation);
  }

  lastCall(operation?: Operation): RecordedCall | undefined {
    return (operation ? this.callsOf(operation) : this.calls).at(-1);
  }

  /** Last mutating call, i.e. the submission the scenario performed. */
  lastMutation(): RecordedCall | undefined {
    return this.calls.filter((call) => MUTATING.has(call.method)).at(-1);
  }

  /** Changes the key server-side without the SPA knowing, as a concurrent edit would. */
  rotateKeyBehindClient(draft: DraftRecord): void {
    draft.confirmationKey = randomUUID();
  }

  // ---------------------------------------------------------------------------------------------

  private async handle(route: Route): Promise<void> {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api\/v1/, '');
    const headers = await request.allHeaders();
    const body = parseBody(request);
    const reply = this.dispatch(request.method(), path, headers, body);
    const call: RecordedCall = {
      operation: ROUTES.find((r) => r.pattern.test(path))?.operation ?? null,
      method: request.method(),
      path,
      status: reply.status,
      requestHeaders: headers,
      requestBody: body,
      responseBody: reply.body,
    };
    this.calls.push(call);
    if (reply.csrfToken !== undefined) {
      // Same cookie as the `Set-Cookie` header below, registered on the context so its presence
      // does not depend on how the interception layer replays response cookies.
      await this.context?.addCookies([
        { name: CSRF_COOKIE, value: reply.csrfToken, url: this.origin, sameSite: 'Strict' },
      ]);
    }
    await route.fulfill({
      status: reply.status,
      headers: { 'Cache-Control': 'no-store', ...(reply.headers ?? {}) },
      contentType: reply.status >= 400 ? 'application/problem+json' : 'application/json',
      body: reply.body === undefined ? '' : JSON.stringify(reply.body),
    });
  }

  private dispatch(
    method: string,
    path: string,
    headers: Record<string, string>,
    body: unknown,
  ): Reply {
    const matching = ROUTES.filter((r) => r.pattern.test(path));
    if (!matching.length) {
      return problem(404, 'Not Found');
    }
    const route = matching.find((r) => r.method === method);
    if (!route) {
      return { ...problem(405, 'Method Not Allowed'), headers: { Allow: matching.map((r) => r.method).join(', ') } };
    }
    if (MUTATING.has(method) && !this.csrfAccepted(headers)) {
      return problem(403, 'Forbidden');
    }
    const param = decodeURIComponent(route.pattern.exec(path)?.[1] ?? '');
    switch (route.operation) {
      case 'issueCsrfToken':
        return this.issueCsrfToken(headers);
      case 'listProducts':
        return {
          status: 200,
          body: this.products.map(({ code, name, profile, annualRate }) => ({ code, name, profile, annualRate })),
        };
      case 'getProduct': {
        const product = this.product(param);
        return product ? { status: 200, body: product } : problem(404, 'Not Found');
      }
      case 'simulate':
        return this.simulate(body as Record<string, unknown>);
      case 'startDraft':
        return this.startDraft(body as Record<string, unknown>);
      case 'getDraft': {
        const draft = this.drafts.get(param);
        return draft ? { status: 200, body: viewOf(draft) } : problem(404, 'Not Found');
      }
      case 'putDraftSubscriber':
        return this.putSubscriber(param, body as Record<string, unknown>);
      case 'putDraftSimulation':
        return this.putSimulation(param, body as Record<string, unknown>);
      case 'confirmDraft':
        return this.confirm(param, body as Record<string, unknown>);
      case 'getSubscription': {
        const found = this.subscriptions.find((s) => s.reference === param);
        return found ? { status: 200, body: confirmationOf(found) } : problem(404, 'Not Found');
      }
    }
  }

  private csrfAccepted(headers: Record<string, string>): boolean {
    const cookie = cookieValue(headers['cookie'] ?? '', CSRF_COOKIE);
    const header = headers[CSRF_HEADER];
    return !!cookie && !!header && header.length <= 100 && header === cookie;
  }

  /** An existing token is kept; a new one is issued as the double-submit cookie otherwise. */
  private issueCsrfToken(headers: Record<string, string>): Reply {
    const existing = cookieValue(headers['cookie'] ?? '', CSRF_COOKIE);
    if (existing) {
      return { status: 200, body: { token: existing } };
    }
    const token = randomUUID().replace(/-/g, '');
    return {
      status: 200,
      csrfToken: token,
      body: { token },
      headers: { 'Set-Cookie': `${CSRF_COOKIE}=${token}; Path=/; SameSite=Strict` },
    };
  }

  private simulate(body: Record<string, unknown>): Reply {
    const product = this.product(String(body['productCode'] ?? ''));
    if (!product) {
      return problem(404, 'Not Found');
    }
    return this.project(product, body, () => undefined);
  }

  private startDraft(body: Record<string, unknown>): Reply {
    const product = this.product(String(body['productCode'] ?? ''));
    if (!product) {
      // Former `commencer.do` with an unknown code: `response.sendError(400)`.
      return problem(400, 'Bad Request');
    }
    const draft: DraftRecord = {
      draftId: randomUUID(),
      product: { ...product },
      subscriber: null,
      simulation: null,
      confirmationKey: randomUUID(),
      confirmed: false,
    };
    this.drafts.set(draft.draftId, draft);
    return { status: 201, body: viewOf(draft), headers: { Location: `/api/v1/drafts/${draft.draftId}` } };
  }

  private putSubscriber(draftId: string, body: Record<string, unknown>): Reply {
    const draft = this.drafts.get(draftId);
    if (!draft || draft.confirmed) {
      return problem(404, 'Not Found');
    }
    const input = {
      firstName: String(body['firstName'] ?? ''),
      lastName: String(body['lastName'] ?? ''),
      email: String(body['email'] ?? ''),
      birthDate: String(body['birthDate'] ?? ''),
    };
    const errors = subscriberErrors(input, this.today);
    if (errors.length) {
      return problem(400, 'Validation échouée', errors);
    }
    draft.subscriber = {
      firstName: input.firstName.trim(),
      lastName: input.lastName.trim(),
      email: input.email.trim(),
      birthDate: input.birthDate,
    };
    draft.confirmationKey = randomUUID();
    return { status: 200, body: viewOf(draft) };
  }

  private putSimulation(draftId: string, body: Record<string, unknown>): Reply {
    const draft = this.drafts.get(draftId);
    if (!draft || draft.confirmed) {
      return problem(404, 'Not Found');
    }
    if (!draft.subscriber) {
      return problem(409, 'Conflict');
    }
    return this.project(draft.product, body, (simulation) => {
      draft.simulation = simulation;
      draft.confirmationKey = randomUUID();
      return { status: 200, body: viewOf(draft) };
    });
  }

  /** `InputValidator.simulationErrors` then `SimulationService.calculate` on the product rate. */
  private project(
    product: ProductDetail,
    body: Record<string, unknown>,
    onSuccess: (simulation: SimulationResult) => Reply | undefined,
  ): Reply {
    const initial = String(body['initialAmount'] ?? '');
    const monthly = String(body['monthlyAmount'] ?? '');
    const years = String(body['durationYears'] ?? '');
    const errors = simulationErrors(initial, monthly, years);
    if (errors.length) {
      return problem(400, 'Validation échouée', errors);
    }
    const simulation = calculate(initial, monthly, Number(years), product.annualRate);
    if (simulation === null) {
      return problem(400, SIMULATION_REJECTED);
    }
    return onSuccess(simulation) ?? { status: 200, body: simulation };
  }

  private confirm(draftId: string, body: Record<string, unknown>): Reply {
    const key = String(body['confirmationKey'] ?? '');
    const existing = this.subscriptions.find((s) => s.confirmationKey === key && s.draftId === draftId);
    if (existing) {
      return { status: 200, body: confirmationOf(existing) };
    }
    const draft = this.drafts.get(draftId);
    if (!draft || draft.confirmed) {
      return problem(404, 'Not Found');
    }
    if (!draft.subscriber || !draft.simulation || draft.confirmationKey !== key) {
      return problem(409, 'Conflict');
    }
    if (this.confirmUnavailable) {
      return problem(503, 'Service Unavailable');
    }
    // The capital is recomputed from the product rate at confirmation, never taken from the client.
    const simulation =
      calculate(
        draft.simulation.initialAmount,
        draft.simulation.monthlyAmount,
        draft.simulation.durationYears,
        draft.product.annualRate,
      ) ?? draft.simulation;
    const record: SubscriptionRecord = {
      reference: `SUB-${this.today.slice(0, 4)}-${String(this.subscriptions.length + 1).padStart(6, '0')}`,
      confirmationKey: key,
      draftId,
      product: draft.product,
      subscriber: draft.subscriber,
      simulation,
      createdAt: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    };
    this.subscriptions.push(record);
    draft.confirmed = true;
    return { status: 201, body: confirmationOf(record), headers: { Location: `/api/v1/subscriptions/${record.reference}` } };
  }

  /** Inserts a subscription for the key of `draft`, as an earlier confirmation would have. */
  seedSubscription(draft: DraftRecord): SubscriptionRecord {
    if (!draft.subscriber || !draft.simulation) {
      throw new Error('seedSubscription: the draft is incomplete');
    }
    const record: SubscriptionRecord = {
      reference: `SUB-${this.today.slice(0, 4)}-${String(this.subscriptions.length + 1).padStart(6, '0')}`,
      confirmationKey: draft.confirmationKey,
      draftId: draft.draftId,
      product: draft.product,
      subscriber: draft.subscriber,
      simulation: draft.simulation,
      createdAt: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    };
    this.subscriptions.push(record);
    return record;
  }
}

// ---- contract views ----------------------------------------------------------------------------

function viewOf(draft: DraftRecord) {
  const status = draft.confirmed
    ? 'CONFIRMED'
    : draft.simulation
      ? 'SIMULATED'
      : draft.subscriber
        ? 'SUBSCRIBER_CAPTURED'
        : 'STARTED';
  return {
    draftId: draft.draftId,
    product: draft.product,
    subscriber: draft.subscriber,
    simulation: draft.simulation,
    confirmationKey: draft.confirmationKey,
    status,
  };
}

function confirmationOf(record: SubscriptionRecord) {
  const { code, name, profile, annualRate } = record.product;
  return {
    reference: record.reference,
    product: { code, name, profile, annualRate },
    subscriber: record.subscriber,
    simulation: record.simulation,
    createdAt: record.createdAt,
  };
}

function parseBody(request: Request): unknown {
  const raw = request.postData();
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

export function cookieValue(cookieHeader: string, name: string): string | undefined {
  for (const part of cookieHeader.split(';')) {
    const [key, ...rest] = part.trim().split('=');
    if (key === name) return rest.join('=');
  }
  return undefined;
}

// ---- InputValidator ----------------------------------------------------------------------------

const ISO_CONTROL = /[\u0000-\u001F\u007F-\u009F]/;
const EMAIL = /^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/;
const AMOUNT = /^[0-9]{1,6}(?:[.,][0-9]{1,2})?$/;
const YEARS = /^(?:[1-9]|[12][0-9]|30)$/;

function nameError(value: string, field: string, label: string): FieldError | null {
  const trimmed = value.trim();
  return trimmed === '' || trimmed.length > 80 || ISO_CONTROL.test(value)
    ? {
        field,
        message: `Saisissez votre ${label} (1 à 80 caractères, sans caractère de contrôle).`,
        code: `${field}.invalid`,
      }
    : null;
}

/** Strict ISO date (the SPA transports `dd/MM/yyyy` as `yyyy-MM-dd`), or null. */
function isoDate(raw: string): string | null {
  const parts = /^(\d{4})-(\d{2})-(\d{2})$/.exec(raw);
  if (!parts) return null;
  const [y, m, d] = [Number(parts[1]), Number(parts[2]), Number(parts[3])];
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d ? raw : null;
}

/** `LocalDate.minusYears(n)`: 29 February falls back to 28 February on a non-leap year. */
function minusYears(iso: string, years: number): string {
  const [y, m, d] = iso.split('-').map(Number);
  const target = y - years;
  const leap = (target % 4 === 0 && target % 100 !== 0) || target % 400 === 0;
  const day = m === 2 && d === 29 && !leap ? 28 : d;
  return `${String(target).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

export function subscriberErrors(input: SubscriberInput, today: string): FieldError[] {
  const errors: FieldError[] = [];
  const first = nameError(input.firstName, 'firstName', 'prénom');
  if (first) errors.push(first);
  const last = nameError(input.lastName, 'lastName', 'nom');
  if (last) errors.push(last);
  const email = input.email.trim();
  if (email.length > 254 || !EMAIL.test(email)) {
    errors.push({
      field: 'email',
      message: 'Saisissez une adresse électronique valide (254 caractères maximum).',
      code: 'email.invalid',
    });
  }
  const birth = isoDate(input.birthDate);
  if (birth === null) {
    errors.push({ field: 'birthDate', message: 'Saisissez une date valide au format JJ/MM/AAAA.', code: 'birthDate.format' });
  } else if (birth > today) {
    errors.push({ field: 'birthDate', message: 'La date de naissance ne peut pas être dans le futur.', code: 'birthDate.future' });
  } else if (birth > minusYears(today, 18)) {
    errors.push({ field: 'birthDate', message: 'La souscription est réservée aux personnes majeures.', code: 'birthDate.minor' });
  }
  return errors;
}

export function simulationErrors(initial: string, monthly: string, years: string): FieldError[] {
  const errors: FieldError[] = [];
  const suffix = ' Utilisez au maximum deux décimales, sans séparateur de milliers.';
  if (!amountWithin(initial, '100', '100000')) {
    errors.push({
      field: 'initialAmount',
      message: `Le versement initial doit être compris entre 100,00 et 100 000,00 €.${suffix}`,
      code: 'initialAmount.range',
    });
  }
  if (!amountWithin(monthly, '0', '2000')) {
    errors.push({
      field: 'monthlyAmount',
      message: `La mensualité doit être comprise entre 0,00 et 2 000,00 €.${suffix}`,
      code: 'monthlyAmount.range',
    });
  }
  if (!YEARS.test(years)) {
    errors.push({
      field: 'durationYears',
      message: 'La durée doit être un nombre entier de 1 à 30 ans.',
      code: 'durationYears.range',
    });
  }
  return errors;
}

function amountWithin(raw: string, min: string, max: string): boolean {
  if (!AMOUNT.test(raw)) return false;
  const value = toFixed(raw.replace(',', '.'));
  return value !== null && value >= toFixed(min)! && value <= toFixed(max)!;
}

// ---- SimulationService on fixed-point BigInt arithmetic ----------------------------------------

const SCALE = 30;
const ONE = 10n ** BigInt(SCALE);

/** Decimal string to a fixed-point BigInt at SCALE digits, or null when not a plain decimal. */
function toFixed(raw: string): bigint | null {
  const parts = /^(-?)(\d+)(?:\.(\d+))?$/.exec(raw.trim());
  if (!parts) return null;
  const fraction = (parts[3] ?? '').padEnd(SCALE, '0').slice(0, SCALE);
  const magnitude = BigInt(parts[2]) * ONE + BigInt(fraction || '0');
  return parts[1] === '-' ? -magnitude : magnitude;
}

function scaleOf(raw: string): number {
  return (raw.split('.')[1] ?? '').length;
}

function divRound(numerator: bigint, denominator: bigint): bigint {
  const q = numerator / denominator;
  const r = numerator % denominator;
  return 2n * (r < 0n ? -r : r) >= denominator ? q + (numerator < 0n ? -1n : 1n) : q;
}

/** Fixed-point value to a scale-2 decimal string, HALF_UP. */
function toAmount(value: bigint): string {
  const cents = divRound(value, 10n ** BigInt(SCALE - 2));
  const sign = cents < 0n ? '-' : '';
  const abs = cents < 0n ? -cents : cents;
  return `${sign}${abs / 100n}.${String(abs % 100n).padStart(2, '0')}`;
}

/** Port of `SimulationService.calculate`; null where the source threw `IllegalArgumentException`. */
export function calculate(initialRaw: string, monthlyRaw: string, years: number, rateRaw: string): SimulationResult | null {
  const initial = toFixed(initialRaw);
  const monthly = toFixed(monthlyRaw);
  const rate = toFixed(rateRaw);
  if (
    initial === null || monthly === null || rate === null ||
    scaleOf(initialRaw) > 2 || scaleOf(monthlyRaw) > 2 ||
    initial < toFixed('100')! || initial > toFixed('100000')! ||
    monthly < 0n || monthly > toFixed('2000')! ||
    !Number.isInteger(years) || years < 1 || years > 30 ||
    rate < 0n || rate > ONE
  ) {
    return null;
  }
  const factor = ONE + divRound(rate, 12n);
  let capital = initial;
  for (let month = 0; month < years * 12; month++) {
    capital = divRound(capital * factor, ONE) + monthly;
  }
  const contributed = initial + monthly * BigInt(years * 12);
  return {
    initialAmount: toAmount(initial),
    monthlyAmount: toAmount(monthly),
    durationYears: years,
    contributed: toAmount(contributed),
    finalCapital: toAmount(capital),
  };
}
