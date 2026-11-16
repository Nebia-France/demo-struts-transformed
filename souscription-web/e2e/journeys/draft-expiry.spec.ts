import { expect, test } from '@playwright/test';
import { PRODUCT, SUBSCRIBER, problem, stubGateway } from '../support/journey-helpers';

/**
 * Journey 3/5: the server draft is gone (410), the legacy 20-minute session timeout.
 *
 * `web.xml` set `<session-timeout>20</session-timeout>` and the servlet forwarded back to the
 * catalogue with the "parcours incomplet" notice. The SPA maps 404/410 to the `expired` family,
 * clears the store and lands on `/catalogue?notice=parcours` — never on an error page.
 */
test('draft expiry: a purged draft sends the user back to the catalogue notice', async ({
  page,
}) => {
  await stubGateway(page, {
    putSubscriber: (route) => problem(route, 410, 'Gone'),
  });

  await page.goto('/catalogue');
  await page.getByRole('link', { name: PRODUCT.name, exact: true }).click();
  await page.getByRole('button', { name: 'Commencer la souscription >' }).click();

  await page.locator('input#firstName').fill(SUBSCRIBER.firstName);
  await page.locator('input#lastName').fill(SUBSCRIBER.lastName);
  await page.locator('input#email').fill(SUBSCRIBER.email);
  await page.locator('input#birthDate').fill('14/03/1985');
  await page.getByRole('button', { name: 'Continuer >' }).click();

  await expect(page).toHaveURL(/\/catalogue\?notice=parcours$/);
  await expect(page.locator('div.message.notice[role="status"]')).toHaveText(
    'Votre parcours est incomplet ou votre session a expiré. Veuillez choisir un produit pour commencer une nouvelle demande.',
  );
  await expect(page.locator('main#contenu h1')).toHaveText('Catalogue des placements');
});
