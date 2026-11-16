import AxeBuilder from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';

/** Impacts that fail the gate (W4-T07); `minor` and `moderate` are reported only. */
const BLOCKING = new Set(['serious', 'critical']);

/**
 * Navigates to `path` and runs axe-core on the rendered route.
 *
 * The page reload of the 7 JSP screens announced the new screen for free; the SPA relies on the
 * single `h1`, the `role="status"` live region of `app-layout` and the `role="alert"` error
 * summary instead, so every route is analysed as a whole document.
 */
export async function expectNoA11yViolations(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expect(page.locator('main#contenu h1')).toBeVisible();

  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();

  const blocking = results.violations.filter((v) => BLOCKING.has(v.impact ?? ''));
  expect(
    blocking,
    blocking.map((v) => `${v.impact}: ${v.id} - ${v.help}`).join('\n'),
  ).toEqual([]);
}
