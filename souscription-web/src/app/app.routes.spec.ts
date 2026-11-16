import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection, signal } from '@angular/core';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, of } from 'rxjs';
import { CatalogueApiService } from './api/catalogue-api.service';
import { SubscriptionApiService } from './api/subscription-api.service';
import type {
  ConfirmationRequest,
  DraftView,
  ProductDetail,
  ProductSummary,
  SubscriptionConfirmation,
} from './api/models';
import { DraftStore } from './wizard/draft.store';
import { WizardFacade } from './wizard/wizard.facade';
import { routes } from './app.routes';

const CATALOGUE: ProductSummary[] = [
  { code: 'PRUDENT', name: 'Horizon Prudent', profile: 'Prudent', annualRate: '0.020000' },
];

const PRODUCT: ProductDetail = {
  code: 'PRUDENT',
  name: 'Horizon Prudent',
  profile: 'Prudent',
  description: 'Support sécurisé.',
  annualRate: '0.020000',
};

class CatalogueApiStub {
  listProducts(): Observable<ProductSummary[]> {
    return of(CATALOGUE);
  }

  getProduct(): Observable<ProductDetail> {
    return of(PRODUCT);
  }
}

class SubscriptionApiStub {
  startedProducts: string[] = [];
  confirmedDrafts: string[] = [];

  startDraft(productCode: string): Observable<DraftView> {
    this.startedProducts.push(productCode);
    return of({ draftId: 'D-1' } as DraftView);
  }

  confirmDraft(draftId: string): Observable<SubscriptionConfirmation> {
    this.confirmedDrafts.push(draftId);
    return of({ reference: 'S-1' } as SubscriptionConfirmation);
  }
}

/**
 * Draft state satisfying the three wizard guards: this suite asserts the URL mapping,
 * the sequencing itself is covered by `wizard.guards.spec.ts`.
 */
const COMPLETE_DRAFT: Partial<DraftStore> = {
  hasDraft: signal(true),
  hasSubscriber: signal(true),
  hasSimulation: signal(true),
  subscriber: signal(null),
  simulation: signal(null),
  product: signal(PRODUCT),
  completedReference: signal('S-1'),
  completed: signal(null),
  expireIfStale: () => false,
};

/**
 * Closes the coverage gap of RAPPORT_COUVERTURE_GHERKIN.md §2: one assertion per row of the
 * legacy front-controller (and `web.xml`) to Angular Router correspondence table of README.md.
 */
describe('app routes (legacy front-controller correspondence)', () => {
  let subscriptions: SubscriptionApiStub;

  beforeEach(() => {
    subscriptions = new SubscriptionApiStub();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter(routes, withComponentInputBinding()),
        { provide: CatalogueApiService, useValue: new CatalogueApiStub() },
        { provide: SubscriptionApiService, useValue: subscriptions },
        { provide: DraftStore, useValue: COMPLETE_DRAFT },
      ],
    });
  });

  async function navigate(url: string): Promise<string> {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    return TestBed.inject(Router).url;
  }

  it("legacy catalogue action welcome-file: '' redirects to /catalogue", async () => {
    expect(await navigate('/')).toBe('/catalogue');
  });

  it('legacy catalogue action resolves the catalogue on /catalogue', async () => {
    expect(await navigate('/catalogue')).toBe('/catalogue');
  });

  it('legacy produit action ?code=X becomes /produit/:code', async () => {
    expect(await navigate('/produit/PRUDENT')).toBe('/produit/PRUDENT');
  });

  it('legacy produit action without a code lands on /erreur/404 instead of a blank sheet', async () => {
    expect(await navigate('/produit')).toBe('/erreur/404');
  });

  it('legacy commencer action (POST) becomes startDraft then navigation to /souscription/souscripteur', async () => {
    const harness = await RouterTestingHarness.create();
    await new Promise<void>((resolve) =>
      TestBed.inject(WizardFacade)
        .start('PRUDENT')
        .subscribe(() => resolve()),
    );
    await harness.navigateByUrl('/souscription/souscripteur');

    expect(subscriptions.startedProducts).toEqual(['PRUDENT']);
    expect(TestBed.inject(Router).url).toBe('/souscription/souscripteur');
  });

  it('legacy souscripteur action becomes /souscription/souscripteur', async () => {
    expect(await navigate('/souscription/souscripteur')).toBe('/souscription/souscripteur');
  });

  it('legacy simulation action becomes /souscription/simulation', async () => {
    expect(await navigate('/souscription/simulation')).toBe('/souscription/simulation');
  });

  it('legacy recapitulatif action becomes /souscription/recapitulatif', async () => {
    expect(await navigate('/souscription/recapitulatif')).toBe('/souscription/recapitulatif');
  });

  it('legacy confirmer action (POST) becomes confirmDraft then navigation to /souscription/confirmation', async () => {
    const harness = await RouterTestingHarness.create();
    await new Promise<void>((resolve) =>
      TestBed.inject(WizardFacade)
        .confirm('D-1', {} as ConfirmationRequest)
        .subscribe(() => resolve()),
    );
    await harness.navigateByUrl('/souscription/confirmation');

    expect(subscriptions.confirmedDrafts).toEqual(['D-1']);
    expect(TestBed.inject(Router).url).toBe('/souscription/confirmation');
  });

  it('legacy confirmation action becomes /souscription/confirmation', async () => {
    expect(await navigate('/souscription/confirmation')).toBe('/souscription/confirmation');
  });

  it('legacy catalogue action?notice=parcours keeps the notice query parameter', async () => {
    expect(await navigate('/catalogue?notice=parcours')).toBe('/catalogue?notice=parcours');
  });

  it('an unknown path lands on /erreur/404 instead of sendError(404)', async () => {
    expect(await navigate('/chemin-inconnu')).toBe('/erreur/404');
  });
});
