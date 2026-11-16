import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { DEFAULT_CURRENCY_CODE, LOCALE_ID, Provider } from '@angular/core';

/**
 * Single declaration of the application locale, replacing
 * `<fmt:setLocale value="fr_FR"/>` of the `fragments/header` fragment: the JSTL tag fixed the
 * formatting locale for every page, so the Angular equivalent must be global too.
 * `DEFAULT_CURRENCY_CODE` reproduces the euro of `fmt:formatNumber type="currency"`.
 */
registerLocaleData(localeFr);

export const localeProviders: Provider[] = [
  { provide: LOCALE_ID, useValue: 'fr-FR' },
  { provide: DEFAULT_CURRENCY_CODE, useValue: 'EUR' },
];
