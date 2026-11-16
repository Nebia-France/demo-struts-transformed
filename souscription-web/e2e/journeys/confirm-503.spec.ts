import { expect, test } from '@playwright/test';
import { CONFIRMATION, REFERENCE, goToSummary, json, problem, stubGateway } from '../support/journey-helpers';

/**
 * Journey 5/5: the store is momentarily unavailable on confirmation (503).
 *
 * The legacy page restarted the whole journey. The SPA keeps the user on the récapitulatif with
 * the input and the ticked acceptance preserved, shows the retryable message in place and
 * relabels the control `Réessayer`, so a retry replays the same `confirmationKey` and cannot
 * create a second subscription.
 */
test('confirmation 503: retryable message in place, then a successful retry', async ({ page }) => {
  let attempts = 0;

  await stubGateway(page, {
    confirmDraft: async (route) => {
      attempts += 1;
      if (attempts === 1) {
        await problem(route, 503, 'Service Unavailable');
        return;
      }
      await json(route, 201, CONFIRMATION);
    },
  });

  await goToSummary(page);
  await page.getByRole('button', { name: 'Confirmer la souscription >' }).click();

  await expect(page).toHaveURL(/\/souscription\/recapitulatif$/);
  await expect(page.locator('p.message.error#storage-error[role="alert"]')).toHaveText(
    "L'enregistrement est momentanément indisponible.",
  );
  await expect(page.locator('input#accepted')).toBeChecked();

  const retry = page.getByRole('button', { name: 'Réessayer' });
  await expect(retry).toBeEnabled();
  await retry.click();

  await expect(page).toHaveURL(/\/souscription\/confirmation$/);
  await expect(page.locator('#reference')).toHaveText(REFERENCE);
  expect(attempts).toBe(2);
});
