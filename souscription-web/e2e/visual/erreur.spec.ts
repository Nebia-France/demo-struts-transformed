import { test } from '@playwright/test';
import { screenshotRoute } from './helpers';

test('/erreur/404 renders with the ported stylesheet', async ({ page }) => {
  await screenshotRoute(page, '/erreur/404', 'erreur-404');
});
