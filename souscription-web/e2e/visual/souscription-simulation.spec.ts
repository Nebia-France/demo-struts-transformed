import { test } from '@playwright/test';
import { screenshotRoute } from './helpers';

test('/souscription/simulation renders with the ported stylesheet', async ({ page }) => {
  await screenshotRoute(page, '/souscription/simulation', 'souscription-simulation');
});
