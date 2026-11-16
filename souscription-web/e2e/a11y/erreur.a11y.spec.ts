import { test } from '@playwright/test';
import { expectNoA11yViolations } from './axe';

test('erreur has no serious or critical accessibility violation', async ({ page }) => {
  await expectNoA11yViolations(page, '/erreur/404');
});
