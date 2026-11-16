import { expect, test } from '@playwright/test';
import { REFERENCE, goToSummary, stubGateway } from '../support/journey-helpers';

/**
 * Journey 1/5: the happy path of `SubscriptionIT`, catalogue to confirmation.
 *
 * Replaces the jsoup assertions on the 7 rendered JSP pages: same checkpoints (product chosen,
 * subscriber captured, simulation retained, récapitulatif accepted, reference issued), driven
 * through the browser instead of through a Tomcat started by Cargo.
 */
test('full journey: catalogue to confirmation with a reference', async ({ page }) => {
  await stubGateway(page);

  await goToSummary(page);
  await expect(page).toHaveURL(/\/souscription\/recapitulatif$/);
  await expect(page.locator('main#contenu h1')).toHaveText('Récapitulatif de la demande');

  await page.getByRole('button', { name: 'Confirmer la souscription >' }).click();

  await expect(page).toHaveURL(/\/souscription\/confirmation$/);
  await expect(page.locator('main#contenu h1')).toHaveText('Confirmation de la souscription');
  await expect(page.locator('.message.success')).toHaveAttribute('role', 'status');
  await expect(page.locator('#reference')).toHaveText(REFERENCE);
  await expect(page.locator('#capital-final')).not.toHaveText('');
});
