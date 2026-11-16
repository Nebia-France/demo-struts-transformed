import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, of } from 'rxjs';
import { CatalogueApiService } from '../api/catalogue-api.service';
import type { ProductSummary } from '../api/models';
import { localeProviders } from '../core/locale/locale.providers';
import { CatalogueListComponent } from './catalogue-list.component';
import { catalogueResolver } from './catalogue.resolvers';
import { expectNoA11yViolations } from '../testing/axe';

const CATALOGUE: ProductSummary[] = [
  { code: 'PRUDENT', name: 'Horizon Prudent', profile: 'Prudent', annualRate: '0.020000' },
  { code: 'DYNAMIQUE', name: 'Horizon Dynamique', profile: 'Dynamique', annualRate: '0.060000' },
];

class CatalogueApiStub {
  listProducts(): Observable<ProductSummary[]> {
    return of(CATALOGUE);
  }
}

describe('CatalogueListComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        ...localeProviders,
        provideRouter(
          [
            {
              path: 'catalogue',
              resolve: { products: catalogueResolver },
              component: CatalogueListComponent,
            },
            { path: 'produit/:code', children: [] },
          ],
          withComponentInputBinding(),
        ),
        { provide: CatalogueApiService, useClass: CatalogueApiStub },
      ],
    });
  });

  async function render(url: string): Promise<HTMLElement> {
    const harness = await RouterTestingHarness.create(url);
    await harness.fixture.whenStable();
    return harness.fixture.nativeElement as HTMLElement;
  }

  it('renders one row per resolved product', async () => {
    const host = await render('/catalogue');

    const rows = host.querySelectorAll('table.catalogue tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].querySelector('th[scope="row"]')?.textContent?.trim()).toBe('Horizon Prudent');
    expect(rows[0].querySelectorAll('td')[0].textContent?.trim()).toBe('Prudent');
  });

  it('keeps the sr-only caption and the column headers of the JSP table', async () => {
    const host = await render('/catalogue');

    expect(host.querySelector('caption.sr-only')?.textContent).toBe(
      'Comparaison des trois placements',
    );
    expect(host.querySelectorAll('th[scope="col"]').length).toBe(5);
  });

  it('formats the annual rate as an integer percentage', async () => {
    const host = await render('/catalogue');

    const rates = host.querySelectorAll('td.rate');
    expect(rates[0].textContent?.trim()).toBe('2 %');
    expect(rates[1].textContent?.trim()).toBe('6 %');
  });

  it('links each product to /produit/<code>', async () => {
    const host = await render('/catalogue');

    const links = host.querySelectorAll<HTMLAnchorElement>('th[scope="row"] a');
    expect(links[0].getAttribute('href')).toBe('/produit/PRUDENT');
    expect(links[1].getAttribute('href')).toBe('/produit/DYNAMIQUE');
  });

  it('shows the role="status" notice only when notice=parcours is set', async () => {
    expect((await render('/catalogue')).querySelector('[role="status"]')).toBeNull();
  });

  it('shows the expired-journey notice when notice=parcours is set', async () => {
    const host = await render('/catalogue?notice=parcours');

    expect(host.querySelector('[role="status"]')?.textContent).toBe(
      'Votre parcours est incomplet ou votre session a expiré. Veuillez choisir un produit pour commencer une nouvelle demande.',
    );
  });
  it('renders /catalogue without serious or critical accessibility violation', async () => {
    const host = await render('/catalogue');

    await expectNoA11yViolations(host);
  });
});
