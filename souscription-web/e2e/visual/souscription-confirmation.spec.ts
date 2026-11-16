import { test } from '@playwright/test';
import { screenshotRoute } from './helpers';

test('/souscription/confirmation renders with the ported stylesheet', async ({ page }) => {
  await screenshotRoute(page, '/souscription/confirmation', 'souscription-confirmation');
});
