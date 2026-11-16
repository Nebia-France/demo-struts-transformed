import { expect, type Page } from '@playwright/test';

/**
 * Fixed `/version.json` of the baselines: the footer renders the deployed release (W9-T04), which
 * changes with every commit, so the screenshots pin it to a constant instead.
 */
const BASELINE_VERSION = {
  release: '0000000000000000000000000000000000000000',
  builtAt: '2026-01-01T00:00:00.000Z',
};

/** Navigates to `path` and asserts the full-page screenshot named `name`. */
export async function screenshotRoute(page: Page, path: string, name: string): Promise<void> {
  await page.route('**/version.json', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(BASELINE_VERSION) }),
  );
  await page.goto(path);
  await expect(page.locator('.site')).toBeVisible();
  await expect(page.locator('[data-testid="release"]')).toBeVisible();
  await expect(page).toHaveScreenshot(`${name}.png`, { fullPage: true });
}
