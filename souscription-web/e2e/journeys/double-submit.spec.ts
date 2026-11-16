import { expect, test } from '@playwright/test';
import { CONFIRMATION, REFERENCE, goToSummary, json, stubGateway } from '../support/journey-helpers';

/**
 * Journey 2/5: a double click on `Confirmer la souscription` must create one subscription only.
 *
 * The legacy guard was `synchronized (session)` inside the servlet. Here the submit control is
 * disabled for the whole round trip and `ConfirmService.inFlight` short-circuits re-entry, so the
 * second click issues no second POST.
 */
test('double submit: two clicks issue a single confirmation request', async ({ page }) => {
  let confirmations = 0;
  let release: (() => void) | undefined;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });

  await stubGateway(page, {
    confirmDraft: async (route) => {
      confirmations += 1;
      await held;
      await json(route, 201, CONFIRMATION);
    },
  });

  await goToSummary(page);

  const submit = page.getByRole('button', { name: 'Confirmer la souscription >' });
  await submit.click();
  await expect(submit).toBeDisabled();
  await expect(page.locator('form[aria-busy="true"]')).toHaveCount(1);

  // Second attempt while the first is still in flight: the control refuses it.
  await submit.click({ force: true, noWaitAfter: true }).catch(() => undefined);

  release?.();

  await expect(page).toHaveURL(/\/souscription\/confirmation$/);
  await expect(page.locator('#reference')).toHaveText(REFERENCE);
  expect(confirmations).toBe(1);
});
