import { test } from '@playwright/test';
import { screenshotRoute } from './helpers';

test('/produit/:code renders with the ported stylesheet', async ({ page }) => {
  await screenshotRoute(page, '/produit/LIV-A', 'produit-code');
});
