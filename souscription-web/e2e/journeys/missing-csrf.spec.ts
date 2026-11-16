import { expect, test } from '@playwright/test';
import { PRODUCT, SUBSCRIBER, problem, stubGateway } from '../support/journey-helpers';

/**
 * Journey 4/5: a mutation without a valid CSRF token is refused by the gateway.
 *
 * `contracts/security-policy.json` requires the double-submit pair (`XSRF-TOKEN` cookie echoed as
 * `X-CSRF-Token`) on every mutating verb; a mismatch is a 403. The SPA must then leave the step
 * for `/erreur/403` with the authentication message of the error catalogue, which is what the
 * legacy `<error-page>` forward did.
 */
test('missing CSRF token: the refused mutation lands on /erreur/403', async ({ page }) => {
  await stubGateway(page, {
    // The gateway answers 403 when the header does not echo the cookie.
    putSubscriber: async (route) => {
      const header = route.request().headers()['x-csrf-token'];
      expect(header, 'the SPA must send the double-submit header').toBeTruthy();
      await problem(route, 403, 'Forbidden');
    },
  });

  await page.goto('/catalogue');
  await page.getByRole('link', { name: PRODUCT.name, exact: true }).click();
  await page.getByRole('button', { name: 'Commencer la souscription >' }).click();

  await page.locator('input#firstName').fill(SUBSCRIBER.firstName);
  await page.locator('input#lastName').fill(SUBSCRIBER.lastName);
  await page.locator('input#email').fill(SUBSCRIBER.email);
  await page.locator('input#birthDate').fill('14/03/1985');
  await page.getByRole('button', { name: 'Continuer >' }).click();

  await expect(page).toHaveURL(/\/erreur\/403$/);
  await expect(page.locator('div.message.error[role="alert"]')).toContainText(
    "Votre demande n'a pas pu être authentifiée. Veuillez recommencer.",
  );
  await expect(page.locator('p.fine-print')).toHaveText('Code : 403');
});
