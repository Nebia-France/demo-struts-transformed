import { test } from '@playwright/test';
import { screenshotRoute } from './helpers';

test('/souscription/recapitulatif renders with the ported stylesheet', async ({ page }) => {
  await screenshotRoute(page, '/souscription/recapitulatif', 'souscription-recapitulatif');
});
