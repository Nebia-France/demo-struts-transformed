import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, of } from 'rxjs';
import { CatalogueApiService } from '../api/catalogue-api.service';
import { SubscriptionApiService } from '../api/subscription-api.service';
import type { DraftView, ProductDetail } from '../api/models';
import { clearDraftRef } from '../wizard/draft-storage';
import { localeProviders } from '../core/locale/locale.providers';
import { ProductDetailComponent } from './product-detail.component';
import { productResolver } from './catalogue.resolvers';

const PRODUCT: ProductDetail = {
  code: 'DYNAMIQUE',
  name: 'Horizon Dynamique',
  profile: 'Dynamique',
  description: 'Support actions.',
  annualRate: '0.060000',
};

class CatalogueApiStub {
  getProduct(): Observable<ProductDetail> {
    return of(PRODUCT);
  }
}

class SubscriptionApiStub {
  startedProducts: string[] = [];

  startDraft(productCode: string): Observable<DraftView> {
    this.startedProducts.push(productCode);
    return of({ draftId: 'D-1', product: PRODUCT, status: 'STARTED' } as DraftView);
  }
}

describe('ProductDetailComponent', () => {
  let subscriptionApi: SubscriptionApiStub;

  beforeEach(() => {
    clearDraftRef();
    subscriptionApi = new SubscriptionApiStub();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        ...localeProviders,
        provideRouter(
          [
            {
              path: 'produit/:code',
              resolve: { product: productResolver },
              component: ProductDetailComponent,
            },
            { path: 'catalogue', children: [] },
            { path: 'souscription/souscripteur', children: [] },
          ],
          withComponentInputBinding(),
        ),
        { provide: CatalogueApiService, useClass: CatalogueApiStub },
        { provide: SubscriptionApiService, useValue: subscriptionApi },
      ],
    });
  });

  async function render(): Promise<RouterTestingHarness> {
    const harness = await RouterTestingHarness.create('/produit/DYNAMIQUE');
    await harness.fixture.whenStable();
    return harness;
  }

  it('renders the resolved product sheet', async () => {
    const host = (await render()).fixture.nativeElement as HTMLElement;

    expect(host.querySelector('h2')?.textContent).toContain('Horizon Dynamique');
    expect(host.textContent).toContain('Support actions.');
    const rows = host.querySelectorAll('table.details tbody tr');
    expect(rows[0].querySelector('th[scope="row"]')?.textContent).toBe('Profil de risque');
    expect(rows[0].querySelector('td')?.textContent?.trim()).toBe('Dynamique');
  });

  it('formats the annual rate as an integer percentage', async () => {
    const host = (await render()).fixture.nativeElement as HTMLElement;

    const rows = host.querySelectorAll('table.details tbody tr');
    expect(rows[1].querySelector('td')?.textContent?.trim()).toBe('6 %');
  });

  it('links back to the catalogue', async () => {
    const host = (await render()).fixture.nativeElement as HTMLElement;

    expect(host.querySelector('.actions a')?.getAttribute('href')).toBe('/catalogue');
  });

  it('starts the draft and enters the wizard on the submit control', async () => {
    const harness = await render();
    const host = harness.fixture.nativeElement as HTMLElement;

    host.querySelector<HTMLButtonElement>('.actions button')?.click();
    await harness.fixture.whenStable();

    expect(subscriptionApi.startedProducts).toEqual(['DYNAMIQUE']);
    expect(TestBed.inject(Router).url).toBe('/souscription/souscripteur');
  });
});
