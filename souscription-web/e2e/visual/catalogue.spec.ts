import { test } from '@playwright/test';
import { screenshotRoute } from './helpers';

test('/catalogue renders with the ported stylesheet', async ({ page }) => {
  await screenshotRoute(page, '/catalogue', 'catalogue');
});
