import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { SimulationApiService } from '../api/simulation-api.service';
import { SubscriptionApiService } from '../api/subscription-api.service';
import type {
  DraftView,
  SimulationResult,
  SubscriberInput,
  SubscriptionConfirmation,
} from '../api/models';
import { WizardFacade } from './wizard.facade';

const product = {
  code: 'PRUDENT',
  name: 'Horizon Prudent',
  profile: 'Prudent',
  description: 'Support sécurisé.',
  annualRate: '0.020000',
};

const subscriber: SubscriberInput = {
  firstName: 'Ada',
  lastName: 'Lovelace',
  email: 'ada@example.org',
  birthDate: '1980-12-10',
};

const simulation: SimulationResult = {
  initialAmount: '1000.00',
  monthlyAmount: '100.00',
  durationYears: 10,
  contributed: '13000.00',
  finalCapital: '14500.00',
};

const draft: DraftView = {
  draftId: '11111111-1111-1111-1111-111111111111',
  product,
  confirmationKey: '22222222-2222-2222-2222-222222222222',
  status: 'STARTED',
};

const confirmation: SubscriptionConfirmation = {
  reference: 'SUB-2024-0001',
  product: {
    code: product.code,
    name: product.name,
    profile: product.profile,
    annualRate: product.annualRate,
  },
  subscriber,
  simulation,
  createdAt: '2024-05-01T10:00:00Z',
};

describe('WizardFacade', () => {
  let facade: WizardFacade;
  let subscriptions: SubscriptionApiService;
  let simulations: SimulationApiService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    facade = TestBed.inject(WizardFacade);
    subscriptions = TestBed.inject(SubscriptionApiService);
    simulations = TestBed.inject(SimulationApiService);
  });

  it('is a root-provided singleton reached by class token', () => {
    expect(TestBed.inject(WizardFacade)).toBe(facade);
  });

  it('delegates start to startDraft', () => {
    const spy = vi.spyOn(subscriptions, 'startDraft').mockReturnValue(of(draft));
    let received: DraftView | undefined;
    facade.start('PRUDENT').subscribe((r) => (received = r));
    expect(spy).toHaveBeenCalledWith('PRUDENT');
    expect(received).toBe(draft);
  });

  it('delegates draft to getDraft', () => {
    const spy = vi.spyOn(subscriptions, 'getDraft').mockReturnValue(of(draft));
    facade.draft(draft.draftId).subscribe();
    expect(spy).toHaveBeenCalledWith(draft.draftId);
  });

  it('delegates saveSubscriber to putDraftSubscriber', () => {
    const spy = vi.spyOn(subscriptions, 'putDraftSubscriber').mockReturnValue(of(draft));
    facade.saveSubscriber(draft.draftId, subscriber).subscribe();
    expect(spy).toHaveBeenCalledWith(draft.draftId, subscriber);
  });

  it('delegates simulate to the simulation service', () => {
    const input = {
      productCode: 'PRUDENT',
      initialAmount: '1000.00',
      monthlyAmount: '100.00',
      durationYears: 10,
    };
    const spy = vi.spyOn(simulations, 'simulate').mockReturnValue(of(simulation));
    let received: SimulationResult | undefined;
    facade.simulate(input).subscribe((r) => (received = r));
    expect(spy).toHaveBeenCalledWith(input);
    expect(received).toBe(simulation);
  });

  it('delegates saveSimulation to putDraftSimulation', () => {
    const spy = vi.spyOn(subscriptions, 'putDraftSimulation').mockReturnValue(of(draft));
    const input = { initialAmount: '1000.00', monthlyAmount: '100.00', durationYears: 10 };
    facade.saveSimulation(draft.draftId, input).subscribe();
    expect(spy).toHaveBeenCalledWith(draft.draftId, input);
  });

  it('delegates confirm to confirmDraft, carrying the idempotency key', () => {
    const spy = vi.spyOn(subscriptions, 'confirmDraft').mockReturnValue(of(confirmation));
    facade.confirm(draft.draftId, { confirmationKey: draft.confirmationKey }).subscribe();
    expect(spy).toHaveBeenCalledWith(draft.draftId, { confirmationKey: draft.confirmationKey });
  });

  it('delegates subscription to getSubscription', () => {
    const spy = vi.spyOn(subscriptions, 'getSubscription').mockReturnValue(of(confirmation));
    facade.subscription('SUB-2024-0001').subscribe();
    expect(spy).toHaveBeenCalledWith('SUB-2024-0001');
  });

  it('holds no mutable state beyond the injected api services', () => {
    vi.spyOn(subscriptions, 'startDraft').mockReturnValue(of(draft));
    vi.spyOn(subscriptions, 'confirmDraft').mockReturnValue(of(confirmation));
    facade.start('PRUDENT').subscribe();
    facade.confirm(draft.draftId, { confirmationKey: draft.confirmationKey }).subscribe();

    expect(Object.getOwnPropertyNames(facade)).toEqual(['subscriptions', 'simulations']);
  });
});
