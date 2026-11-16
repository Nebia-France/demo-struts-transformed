import { test } from '@playwright/test';
import { expectNoA11yViolations } from './axe';

test('simulation has no serious or critical accessibility violation', async ({ page }) => {
  await expectNoA11yViolations(page, '/souscription/simulation');
});
