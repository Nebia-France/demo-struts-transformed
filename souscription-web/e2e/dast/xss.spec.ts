import { expect, test } from '@playwright/test';
import type { Page, Route } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import {
  CONFIRMATION,
  PRODUCT,
  PRODUCT_SUMMARY,
  SIMULATED_DRAFT,
  STARTED_DRAFT,
  SUBSCRIBER_DRAFT,
  json,
  problem,
  stubGateway,
} from '../support/journey-helpers';

/**
 * DAST pass of `deploy/dast.md` (W9-T03): reflected and stored XSS probes over the full journey.
 *
 * Runs against the production bundle (`perf-server.mjs`, project `dast`) with the payload set of
 * `deploy/dast.md` injected in every input, every path parameter, the only rendered query
 * parameter, and every server-provided value (catalogue, draft, confirmation) — the gateway stub
 * stores what the SPA submits and returns it on the next reads, which is where a stored XSS would
 * surface on the récapitulatif and the confirmation.
 *
 * Detection, per crawled state:
 * - an executed probe: `alert`/`confirm`/`prompt` are trapped by an init script and any call is a
 *   finding (the perf server sends no CSP, so the template escaping is tested on its own);
 * - an injected node: any `script` / `img` / `svg` / `iframe` carrying the probe, any `on*`
 *   attribute or any `javascript:` URL in the rendered DOM;
 * - an API answer rendered as markup: a `/api/v1` response with an HTML content type.
 *
 * The report is written to `e2e/reports/dast.json` and quoted in `docs/SECURITY_AUDIT.md`.
 * Pass criterion: zero finding; each probe fails on the finding it raises.
 */

const PAYLOADS = [
  '<script>alert(1)</script>',
  '"><img src=x onerror=alert(1)>',
  `'"--><svg onload=alert(1)>`,
  'javascript:alert(1)',
  "{{constructor.constructor('alert(1)')()}}",
  '%3Cscript%3Ealert(1)%3C%2Fscript%3E',
];

interface Finding {
  readonly state: string;
  readonly payload: string;
  readonly kind: string;
  readonly detail: string;
}

const findings: Finding[] = [];
const crawled: string[] = [];

async function armPage(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const w = window as unknown as { __xss: string[] };
    w.__xss = [];
    for (const name of ['alert', 'confirm', 'prompt'] as const) {
      (window as unknown as Record<string, unknown>)[name] = (message?: unknown) => {
        w.__xss.push(`${name}(${String(message)})`);
        return null;
      };
    }
  });
  page.on('dialog', (dialog) => {
    findings.push({ state: page.url(), payload: '', kind: 'dialog', detail: dialog.message() });
    void dialog.dismiss();
  });
  page.on('response', (response) => {
    if (!response.url().includes('/api/v1/')) return;
    const type = response.headers()['content-type'] ?? '';
    if (/text\/html/i.test(type)) {
      findings.push({ state: response.url(), payload: '', kind: 'api-html', detail: type });
    }
  });
}

/** Inspects the current state for an executed or injected probe. */
async function inspect(
  page: Page,
  state: string,
  payload: string,
  sink: Finding[] = findings,
): Promise<void> {
  // Give deferred handlers (img onerror, svg onload) a chance to fire.
  await page.waitForTimeout(150);
  if (sink === findings) crawled.push(state);
  const result = await page.evaluate(() => {
    const issues: string[] = [];
    const w = window as unknown as { __xss?: string[] };
    for (const call of w.__xss ?? []) issues.push(`executed:${call}`);
    for (const el of Array.from(document.querySelectorAll('body *'))) {
      for (const attr of Array.from(el.attributes)) {
        if (/^on/i.test(attr.name)) issues.push(`handler:<${el.tagName.toLowerCase()} ${attr.name}>`);
        if (/^\s*javascript:/i.test(attr.value) && /^(href|src|action|formaction|xlink:href)$/i.test(attr.name)) {
          issues.push(`js-url:<${el.tagName.toLowerCase()} ${attr.name}="${attr.value}">`);
        }
      }
      const tag = el.tagName.toLowerCase();
      if (['script', 'iframe', 'object', 'embed'].includes(tag) && /alert\(1\)/.test(el.outerHTML)) {
        issues.push(`node:<${tag}>`);
      }
      if (tag === 'img' && el.getAttribute('src') === 'x') issues.push('node:<img src=x>');
      if (tag === 'svg' && /alert\(1\)/.test(el.outerHTML)) issues.push('node:<svg onload>');
    }
    return issues;
  });
  for (const detail of result) sink.push({ state, payload, kind: 'dom', detail });
}

/** Gateway stub that stores the submitted draft and returns it, as a real draft store would. */
async function storingGateway(page: Page, payload: string): Promise<void> {
  let subscriber: Record<string, unknown> = { ...SUBSCRIBER_DRAFT.subscriber };
  const body = (route: Route) => (route.request().postDataJSON() ?? {}) as Record<string, unknown>;
  await stubGateway(page, {
    putSubscriber: async (route) => {
      subscriber = { ...body(route) };
      await json(route, 200, { ...SUBSCRIBER_DRAFT, subscriber });
    },
    putSimulation: (route) => json(route, 200, { ...SIMULATED_DRAFT, subscriber }),
    getDraft: (route) => json(route, 200, { ...SIMULATED_DRAFT, subscriber }),
    // Server-generated values are probes too: a compromised or buggy backend must not be able
    // to inject markup through the reference either.
    confirmDraft: (route) =>
      json(route, 201, { ...CONFIRMATION, subscriber, reference: payload }),
  });
}

test.describe('DAST: reflected and stored XSS (deploy/dast.md)', () => {
  let before = 0;
  test.beforeEach(() => {
    before = findings.length;
  });
  // Pass criterion of deploy/dast.md: any finding fails the probe that raised it.
  test.afterEach(() => {
    const raised = findings.slice(before);
    expect(raised, JSON.stringify(raised, null, 2)).toEqual([]);
  });

  test.afterAll(() => {
    mkdirSync('e2e/reports', { recursive: true });
    writeFileSync(
      'e2e/reports/dast.json',
      JSON.stringify(
        { payloads: PAYLOADS.length, statesCrawled: crawled.length, findings: findings.length, details: findings },
        null,
        2,
      ) + '\n',
    );
  });

  // Positive control: the detector must flag an injected node and an executed probe, otherwise a
  // green pass would prove nothing.
  test('detector self-test: an injected probe is reported', async ({ page }) => {
    await armPage(page);
    await stubGateway(page);
    await page.goto('/catalogue');
    await page.locator('main#contenu').waitFor();
    await page.evaluate(() => {
      const host = document.createElement('div');
      host.innerHTML = '<img src=x onerror="alert(1)">';
      document.body.appendChild(host);
    });
    const control: Finding[] = [];
    await inspect(page, 'self-test', PAYLOADS[1], control);
    const details = control.map((finding) => finding.detail);
    expect(details).toContain('node:<img src=x>');
    expect(details.some((detail) => detail.startsWith('handler:'))).toBe(true);
    expect(details).toContain('executed:alert(1)');
  });

  for (const [index, payload] of PAYLOADS.entries()) {
    test(`reflected: path and query parameters, payload ${index + 1}`, async ({ page }) => {
      await armPage(page);
      await stubGateway(page, {
        getProduct: async (route) => {
          const code = decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop() ?? '');
          if (code === PRODUCT.code) await json(route, 200, PRODUCT);
          else await problem(route, 404, 'Not Found');
        },
      });
      // Encoded once (what a link would carry) and raw (what a hand-typed URL carries).
      for (const segment of [encodeURIComponent(payload), payload]) {
        for (const path of [`/produit/${segment}`, `/erreur/${segment}`, `/catalogue?notice=${segment}`]) {
          await page.goto(path);
          await page.locator('main#contenu').waitFor();
          await inspect(page, path, payload);
        }
      }
    });

    test(`stored: server-provided catalogue values, payload ${index + 1}`, async ({ page }) => {
      await armPage(page);
      const hostile = { ...PRODUCT, name: payload, profile: payload, description: payload };
      await stubGateway(page, {
        listProducts: (route) =>
          json(route, 200, [{ ...PRODUCT_SUMMARY, name: payload, profile: payload }]),
        getProduct: (route) => json(route, 200, hostile),
        startDraft: (route) => json(route, 201, { ...STARTED_DRAFT, product: hostile }),
      });
      await page.goto('/catalogue');
      await page.locator('main#contenu').waitFor();
      await inspect(page, '/catalogue (hostile product list)', payload);
      await page.goto(`/produit/${PRODUCT.code}`);
      await page.locator('main#contenu').waitFor();
      await inspect(page, `/produit/${PRODUCT.code} (hostile product)`, payload);
    });

    test(`stored: every journey field through the draft, payload ${index + 1}`, async ({ page }) => {
      await armPage(page);
      await storingGateway(page, payload);

      await page.goto('/catalogue');
      await page.getByRole('link', { name: PRODUCT.name, exact: true }).click();
      await page.getByRole('button', { name: 'Commencer la souscription >' }).click();

      // Reflected: an invalid birth date keeps every probe echoed back into the form.
      for (const id of ['firstName', 'lastName', 'email', 'birthDate']) {
        await page.locator(`input#${id}`).fill(payload);
      }
      await page.getByRole('button', { name: 'Continuer >' }).click();
      await inspect(page, '/souscription/souscripteur (rejected, echoed)', payload);

      // Stored: the probes are accepted into the draft with a valid birth date.
      await page.locator('input#birthDate').fill('14/03/1985');
      await page.getByRole('button', { name: 'Continuer >' }).click();
      await page.locator('input#initial').waitFor();
      await inspect(page, '/souscription/simulation', payload);

      // Reflected: every simulation field refused client-side, then restored.
      const defaults: Record<string, string> = {};
      for (const id of ['initial', 'monthly', 'years']) {
        defaults[id] = await page.locator(`input#${id}`).inputValue();
        await page.locator(`input#${id}`).fill(payload);
      }
      await page.getByRole('button', { name: 'Continuer >' }).click();
      await inspect(page, '/souscription/simulation (rejected, echoed)', payload);
      for (const [id, value] of Object.entries(defaults)) await page.locator(`input#${id}`).fill(value);
      await page.getByRole('button', { name: 'Continuer >' }).click();

      await expect(page).toHaveURL(/\/souscription\/recapitulatif$/);
      await inspect(page, '/souscription/recapitulatif (stored values re-read)', payload);
      // The probe is rendered as visible text, not interpreted.
      await expect(page.locator('main#contenu')).toContainText(payload);

      await page.locator('input#accepted').check();
      await page.getByRole('button', { name: 'Confirmer la souscription >' }).click();
      await expect(page).toHaveURL(/\/souscription\/confirmation$/);
      await inspect(page, '/souscription/confirmation (stored values + reference)', payload);
    });
  }
});
