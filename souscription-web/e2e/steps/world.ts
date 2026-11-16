import { After, AfterAll, Before, BeforeAll, setDefaultTimeout, setWorldConstructor, World } from '@cucumber/cucumber';
import type { ITestCaseHookParameter, IWorldOptions } from '@cucumber/cucumber';
import { chromium } from '@playwright/test';
import type { Browser, BrowserContext, Page } from '@playwright/test';
import { execSync, spawn } from 'node:child_process';
import type { ChildProcess } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { join } from 'node:path';
import { CSRF_COOKIE, FakeGateway } from '../support/fake-gateway';

/**
 * World of the behavioural gate (`npm run e2e`, W8-T04).
 *
 * Every scenario drives the production SPA in Chromium. Unless `E2E_BASE_URL` points at a served
 * artifact, `BeforeAll` builds `dist/` when it is missing and starts `scripts/e2e-server.mjs`,
 * which serves it with the headers of `deploy/nginx.conf`. Each scenario gets a fresh browser
 * context (no cookie, no `sessionStorage`) and a fresh gateway test double
 * (`e2e/support/fake-gateway.ts`) answering the SPA's `/api/v1` calls.
 */
setDefaultTimeout(60_000);

export const ROOT = join(__dirname, '..', '..');
const E2E_PORT = Number(process.env.E2E_PORT ?? 4400);
const BASE_URL = process.env.E2E_BASE_URL ?? `http://localhost:${E2E_PORT}`;

/**
 * Legacy Struts paths quoted by the Gherkin and the SPA routes that replaced them
 * (table of `src/app/app.routes.ts`).
 */
const LEGACY_ROUTES: Readonly<Record<string, string>> = {
  '/catalogue.do': '/catalogue',
  '/souscripteur.do': '/souscription/souscripteur',
  '/simulation.do': '/souscription/simulation',
  '/recapitulatif.do': '/souscription/recapitulatif',
  '/confirmation.do': '/souscription/confirmation',
};

/** Valid subscriber of the journeys, as typed on screen. */
export const VALID_SUBSCRIBER = {
  firstName: 'Camille',
  lastName: 'Durand',
  email: 'camille.durand@example.fr',
  birthDate: '12/04/1985',
};

let browser: Browser | undefined;
let server: ChildProcess | undefined;

export interface RawResponse {
  status: number;
  headers: Record<string, string>;
}

export class SouscriptionWorld extends World {
  /** Origin the browser uses; a replica scenario points it at its round-robin front. */
  baseURL = BASE_URL;
  context!: BrowserContext;
  page!: Page;
  gateway!: FakeGateway;
  /** Result of the last client-side amount normalisation (locale module). */
  normalisedAmount?: string | null;
  /** Values held by the subscription form model under test (wizard form models). */
  formValues?: Record<string, string>;
  /** Acceptance flag of the form model, former `SubscriptionForm.accepted`. */
  formAccepted?: boolean;
  /** Status and lower-cased headers of the last raw HTTP call on the served artifact. */
  lastResponse?: RawResponse;
  /** Path, method and headers of the last raw HTTP call. */
  lastRequest?: { path: string; method: string; headers: Record<string, string> };
  /**
   * Status the scenario observed through the SPA when it is not carried by a page: a refusal
   * before transport, or a gateway answer to a call replayed from the SPA origin.
   */
  spaStatus?: number;
  /** Domain error the last attempted operation raised (former `IllegalArgumentException`/`IllegalStateException`). */
  raisedError?: string;
  /** Form step whose fields were typed and not submitted yet. */
  unsubmitted?: 'subscriber' | 'simulation';
  /** Confirmation key of the draft before the scenario's action, to observe its rotation. */
  previousKey?: string;
  /** Product code the simulation scenarios are run for. */
  retainedProduct?: string;
  /** Body of the last subscription read through the gateway. */
  lookedUp?: Record<string, unknown>;

  /** Pickle and Gherkin document of the running scenario. */
  source?: Pick<ITestCaseHookParameter, 'pickle' | 'gherkinDocument'>;

  constructor(options: IWorldOptions) {
    super(options);
  }

  /**
   * Value of an `Examples` cell with its leading whitespace. Gherkin trims cells, so a row such as
   * `|  PRUDENT   |` reaches the step as `PRUDENT`; a table pads a cell with exactly one leading
   * space (alignment pads on the right only), so any further leading space is part of the value.
   */
  exampleCell(value: string): string {
    const rowId = this.source?.pickle.astNodeIds[1];
    const document = this.source?.gherkinDocument;
    if (!rowId || !document?.feature) return value;
    for (const child of document.feature.children) {
      for (const examples of child.scenario?.examples ?? []) {
        const row = examples.tableBody.find((r) => r.id === rowId);
        if (!row) continue;
        const index = row.cells.findIndex((cell) => cell.value === value);
        if (index < 0) return value;
        const line = readFileSync(join(ROOT, document.uri ?? ''), 'utf8').split(/\r?\n/)[row.location.line - 1];
        const raw = line.split('|')[index + 1] ?? '';
        const leading = /^\s*/.exec(raw)![0].length;
        return ' '.repeat(Math.max(0, leading - 1)) + value;
      }
    }
    return value;
  }

  // ---- raw HTTP -------------------------------------------------------------------------------

  /** Raw HTTP call on the served artifact (any method token, including TRACE and CONNECT). */
  async call(path: string, method = 'GET', headers: Record<string, string> = {}): Promise<RawResponse> {
    const url = new URL(path, this.baseURL);
    const send = url.protocol === 'https:' ? httpsRequest : httpRequest;
    this.lastRequest = { path, method, headers };
    this.lastResponse = await new Promise<RawResponse>((resolve, reject) => {
      const req = send(url, { method, headers }, (res) => {
        res.resume();
        res.on('end', () => {
          const out: Record<string, string> = {};
          for (const [name, value] of Object.entries(res.headers)) {
            out[name.toLowerCase()] = Array.isArray(value) ? value.join(', ') : String(value ?? '');
          }
          resolve({ status: res.statusCode ?? 0, headers: out });
        });
      });
      req.on('error', reject);
      req.end();
    });
    return this.lastResponse;
  }

  requireLastResponse(): RawResponse {
    if (!this.lastResponse) {
      throw new Error("Aucune reponse HTTP n'a ete collectee pour ce scenario.");
    }
    return this.lastResponse;
  }

  // ---- SPA navigation -------------------------------------------------------------------------

  /** SPA route replacing a legacy `*.do` path (query string preserved). */
  spaRoute(legacy: string): string {
    const [path, query] = legacy.split('?');
    const route = LEGACY_ROUTES[path];
    if (!route) {
      throw new Error(`Chemin herite sans route SPA equivalente : ${legacy}`);
    }
    return query ? `${route}?${query}` : route;
  }

  isLegacyRoute(path: string): boolean {
    return path.split('?')[0] in LEGACY_ROUTES;
  }

  /** Loads `path` as a typed URL (full document load: the SPA bootstraps and rehydrates). */
  async open(path: string): Promise<void> {
    await this.page.goto(new URL(path, this.baseURL).toString());
    await this.settle();
  }

  /** Path and query of the current SPA URL. */
  location(): string {
    const url = new URL(this.page.url());
    return `${url.pathname}${url.search}`;
  }

  async expectLocation(expected: string): Promise<void> {
    const target = new URL(expected, this.baseURL);
    await this.page.waitForURL(
      (url) => url.pathname === target.pathname && url.search === target.search,
      { timeout: 15_000 },
    );
  }

  /** Gateway calls issued by the page and not answered yet. */
  inFlight = 0;
  /** Highest number of gateway calls in flight at the same time during the scenario. */
  maxInFlight = 0;
  /** Resources a scenario started and `After` releases (replicas, proxies). */
  readonly cleanups: (() => Promise<void> | void)[] = [];

  /**
   * Opens a fresh browser context on `baseURL` (no cookie, no `sessionStorage`) with a fresh
   * gateway test double; the previous context of the scenario, if any, is closed.
   */
  async openContext(baseURL = this.baseURL): Promise<void> {
    if (!browser) {
      throw new Error('Chromium is not launched.');
    }
    await this.page?.close();
    await this.context?.close();
    this.baseURL = baseURL;
    this.inFlight = 0;
    this.context = await browser.newContext({ baseURL, locale: 'fr-FR', timezoneId: 'Europe/Paris' });
    this.page = await this.context.newPage();
    const isApi = (url: string) => new URL(url).pathname.startsWith('/api/');
    this.page.on('request', (r) => {
      if (!isApi(r.url())) return;
      this.inFlight++;
      this.maxInFlight = Math.max(this.maxInFlight, this.inFlight);
    });
    this.page.on('requestfinished', (r) => { if (isApi(r.url())) this.inFlight--; });
    this.page.on('requestfailed', (r) => { if (isApi(r.url())) this.inFlight--; });
    // The telemetry collector (src/runtime-config.json) is not part of the gateway contract.
    await this.page.route('**/telemetry/**', (route) => route.fulfill({ status: 204 }));
    this.gateway = new FakeGateway();
    await this.gateway.install(this.page, baseURL);
  }

  /** Waits until no gateway call has been in flight for a short quiet period. */
  async idle(): Promise<void> {
    let quiet = 0;
    for (let i = 0; i < 300 && quiet < 3; i++) {
      await this.page.waitForTimeout(50);
      quiet = this.inFlight === 0 ? quiet + 1 : 0;
    }
  }

  /** Waits until the SPA has rendered and no gateway call is in flight. */
  async settle(): Promise<void> {
    await this.page.locator('main#contenu').waitFor({ state: 'attached', timeout: 15_000 });
    await this.idle();
  }

  /** Status of the error page the SPA displays, if any (`/erreur/:code`). */
  errorPageStatus(): number | undefined {
    const match = /^\/erreur\/(\d{3})$/.exec(new URL(this.page.url()).pathname);
    return match ? Number(match[1]) : undefined;
  }

  /**
   * HTTP status the scenario observed: an explicit SPA-side status, the error page the SPA
   * landed on, the gateway refusal it rendered in place, or the last raw call.
   */
  observedStatus(): number {
    const status =
      this.spaStatus ?? this.errorPageStatus() ?? this.gatewayRefusal() ?? this.lastResponse?.status;
    if (status === undefined) {
      throw new Error("Aucun statut HTTP n'a ete observe pour ce scenario.");
    }
    return status;
  }

  private gatewayRefusal(): number | undefined {
    const status = this.gateway.lastMutation()?.status;
    return status !== undefined && status >= 400 ? status : undefined;
  }

  /** Current XSRF-TOKEN cookie of the browser context. */
  async csrfCookie(): Promise<string | undefined> {
    const cookies = await this.context.cookies(this.baseURL);
    return cookies.find((c) => c.name === CSRF_COOKIE)?.value;
  }

  async setCsrfCookie(value: string): Promise<void> {
    await this.context.addCookies([{ name: CSRF_COOKIE, value, url: this.baseURL, sameSite: 'Strict' }]);
  }

  /** Index (1..5) of the current step of the step rail, 0 when none is current. */
  async currentStep(): Promise<number> {
    const items = this.page.locator('ol.steps > li');
    const count = await items.count();
    for (let i = 0; i < count; i++) {
      if ((await items.nth(i).getAttribute('class'))?.split(/\s+/).includes('current')) {
        return i + 1;
      }
    }
    return 0;
  }

  // ---- journey --------------------------------------------------------------------------------

  /** Opens the product sheet and starts the journey (former `commencer.do`). */
  async startJourney(code: string): Promise<boolean> {
    await this.open(`/produit/${encodeURIComponent(code)}`);
    const start = this.page.getByRole('button', { name: 'Commencer la souscription >' });
    if ((await start.count()) === 0) {
      return false;
    }
    await start.click();
    await this.expectLocation('/souscription/souscripteur');
    await this.settle();
    return true;
  }

  /**
   * Types `value` in the input `#id`. The `maxlength` attribute is a typing affordance only: a
   * value longer than it is set through the DOM, as a paste or an autofill can, so the reactive
   * validators are what is asserted.
   */
  async type(id: string, value: string): Promise<void> {
    const input = this.page.locator(`input#${id}`);
    await input.fill(value);
    if ((await input.inputValue()) !== value) {
      await input.evaluate((el, v) => {
        const field = el as HTMLInputElement;
        field.value = v;
        field.dispatchEvent(new Event('input', { bubbles: true }));
      }, value);
    }
    await input.blur();
  }

  async fillSubscriber(values: Partial<typeof VALID_SUBSCRIBER>): Promise<void> {
    for (const [id, value] of Object.entries(values)) {
      await this.type(id, value);
    }
    this.unsubmitted = 'subscriber';
  }

  async fillSimulation(values: { initial?: string; monthly?: string; years?: string }): Promise<void> {
    for (const [id, value] of Object.entries(values)) {
      if (value !== undefined) await this.type(id, value);
    }
    this.unsubmitted = 'simulation';
  }

  /** Clicks `name` and waits for the gateway answer, if any, and the resulting render. */
  async press(name: string): Promise<void> {
    await this.page.getByRole('button', { name, exact: true }).click();
    await this.idle();
    this.unsubmitted = undefined;
  }

  async submitSubscriber(): Promise<void> {
    await this.press('Continuer >');
  }

  /** Submits typed fields, if any, before an assertion reads the step. */
  async flush(): Promise<void> {
    if (this.unsubmitted === 'subscriber') {
      await this.submitSubscriber();
    } else if (this.unsubmitted === 'simulation') {
      await this.press('Recalculer la simulation');
    }
  }

  /** Step 2 completed with a valid subscriber: the SPA stands on the simulation step. */
  async captureValidSubscriber(code: string): Promise<void> {
    if (!(await this.startJourney(code))) {
      throw new Error(`Le parcours n'a pas pu demarrer pour le produit ${code}.`);
    }
    await this.fillSubscriber(VALID_SUBSCRIBER);
    await this.submitSubscriber();
    await this.expectLocation('/souscription/simulation');
    await this.settle();
  }

  /** Step 3 completed with the default amounts: the SPA stands on the summary step. */
  async recordSimulation(code: string): Promise<void> {
    await this.captureValidSubscriber(code);
    await this.press('Continuer >');
    await this.expectLocation('/souscription/recapitulatif');
    await this.settle();
  }

  /** `aria-invalid` of the input `#id`, which the step sets for client and server refusals. */
  async fieldInError(id: string): Promise<boolean> {
    const input = this.page.locator(`input#${id}`);
    if ((await input.count()) === 0) {
      return false;
    }
    return (await input.getAttribute('aria-invalid')) === 'true';
  }

  async fieldMessage(id: string): Promise<string> {
    const message = this.page.locator(`#${id}-error`);
    return (await message.count()) === 0 ? '' : ((await message.textContent()) ?? '').trim();
  }

  async storedDraftRef(): Promise<string | null> {
    return this.page.evaluate(() => sessionStorage.getItem('souscription.draft'));
  }

  /** Issues a gateway call from the SPA origin with the double-submit header, as the SPA does. */
  async fromSpaOrigin(method: string, path: string, body?: unknown): Promise<{ status: number; body: unknown }> {
    const token = (await this.csrfCookie()) ?? '';
    return this.page.evaluate(
      async ({ method, path, body, token }) => {
        const response = await fetch(path, {
          method,
          headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': token },
          body: body === undefined ? undefined : JSON.stringify(body),
        });
        const text = await response.text();
        return { status: response.status, body: text ? JSON.parse(text) : null };
      },
      { method, path, body, token },
    );
  }
}

setWorldConstructor(SouscriptionWorld);

export async function waitForServer(url: string): Promise<void> {
  for (let i = 0; i < 100; i++) {
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {
      // not listening yet
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`e2e-server n'a pas repondu sur ${url}.`);
}

BeforeAll({ timeout: 600_000 }, async function () {
  if (!process.env.E2E_BASE_URL) {
    if (!existsSync(join(ROOT, 'dist/souscription-web/browser/index.html'))) {
      execSync('npm run build', { cwd: ROOT, stdio: 'inherit' });
    }
    server = spawn(process.execPath, ['scripts/e2e-server.mjs'], {
      cwd: ROOT,
      env: { ...process.env, E2E_PORT: String(E2E_PORT) },
      stdio: 'ignore',
    });
    await waitForServer(`${BASE_URL}/healthz`);
  }
  browser = await chromium.launch({ headless: true });
});

AfterAll(async function () {
  await browser?.close();
  browser = undefined;
  server?.kill();
  server = undefined;
});

Before(async function (this: SouscriptionWorld, { pickle, gherkinDocument }: ITestCaseHookParameter) {
  this.source = { pickle, gherkinDocument };
  await this.openContext();
});

After(async function (this: SouscriptionWorld) {
  await this.page?.close();
  await this.context?.close();
  for (const cleanup of this.cleanups.reverse()) {
    await cleanup();
  }
});
