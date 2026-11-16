import { test } from '@playwright/test';
import { screenshotRoute } from './helpers';

test('/souscription/souscripteur renders with the ported stylesheet', async ({ page }) => {
  await screenshotRoute(page, '/souscription/souscripteur', 'souscription-souscripteur');
});
