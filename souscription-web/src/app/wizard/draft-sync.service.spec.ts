import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { clearDraftRef, readRawDraftRef } from './draft-storage';
import { Router, provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { errorInterceptor } from '../core/http/error.interceptor';
import type { DraftView, SimulationInput, SubscriberInput } from '../api/models';
import { DraftSyncService } from './draft-sync.service';
import { DraftStore } from './draft.store';

const DRAFT_ID = '7f1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d';
const KEY = '1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';

const PRODUCT: DraftView['product'] = {
  code: 'PRUDENT',
  name: 'Horizon Prudent',
  profile: 'Prudent',
  description: 'Support sécurisé.',
  annualRate: '0.020000',
};

const SUBSCRIBER: SubscriberInput = {
  firstName: 'Camille',
  lastName: 'Durand',
  email: 'camille.durand@example.com',
  birthDate: '1985-03-14',
};

const SIMULATION: SimulationInput = {
  initialAmount: '1000.00',
  monthlyAmount: '100.00',
  durationYears: 10,
};

const draftView = (overrides: Partial<DraftView> = {}): DraftView => ({
  draftId: DRAFT_ID,
  product: PRODUCT,
  confirmationKey: KEY,
  status: 'STARTED',
  ...overrides,
});

describe('DraftSyncService', () => {
  let service: DraftSyncService;
  let store: DraftStore;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    clearDraftRef();
    TestBed.configureTestingModule({
      providers: [
        // The error interceptor is part of the production chain: the service under test sees the
        // mapped AppError families, never a raw HttpErrorResponse.
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    service = TestBed.inject(DraftSyncService);
    store = TestBed.inject(DraftStore);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('start() POSTs the draft once and seeds the store from the response', () => {
    service.start('PRUDENT').subscribe();

    const req = httpMock.expectOne('/api/v1/drafts');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ productCode: 'PRUDENT' });
    req.flush(draftView());

    expect(store.draftId()).toBe(DRAFT_ID);
    expect(store.confirmationKey()).toBe(KEY);
    expect(store.product()?.code).toBe('PRUDENT');
    expect(store.hasDraft()).toBe(true);
  });

  it('saveSubscriber() PUTs the subscriber once and applies the server draft', () => {
    store.start(PRODUCT, DRAFT_ID, KEY);

    service.saveSubscriber(SUBSCRIBER).subscribe();

    const req = httpMock.expectOne(`/api/v1/drafts/${DRAFT_ID}/subscriber`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(SUBSCRIBER);
    req.flush(draftView({ subscriber: SUBSCRIBER, status: 'SUBSCRIBER_CAPTURED' }));

    expect(store.subscriber()).toEqual(SUBSCRIBER);
    expect(store.hasSubscriber()).toBe(true);
  });

  it('saveSimulation() PUTs the simulation once and applies the server draft', () => {
    store.start(PRODUCT, DRAFT_ID, KEY);

    service.saveSimulation(SIMULATION).subscribe();

    const req = httpMock.expectOne(`/api/v1/drafts/${DRAFT_ID}/simulation`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(SIMULATION);
    req.flush(
      draftView({
        subscriber: SUBSCRIBER,
        simulation: { ...SIMULATION, contributed: '13000.00', finalCapital: '14000.00' },
        status: 'SIMULATED',
      }),
    );

    expect(store.hasSimulation()).toBe(true);
    expect(store.simulation()?.finalCapital).toBe('14000.00');
  });

  it('serialises two rapid mutations: the second request starts only after the first completes', () => {
    store.start(PRODUCT, DRAFT_ID, KEY);

    service.saveSubscriber(SUBSCRIBER).subscribe();
    service.saveSubscriber({ ...SUBSCRIBER, firstName: 'Claire' }).subscribe();

    const first = httpMock.match(`/api/v1/drafts/${DRAFT_ID}/subscriber`);
    expect(first.length).toBe(1);
    expect(first[0].request.body).toEqual(SUBSCRIBER);

    first[0].flush(draftView({ subscriber: SUBSCRIBER, status: 'SUBSCRIBER_CAPTURED' }));

    const second = httpMock.match(`/api/v1/drafts/${DRAFT_ID}/subscriber`);
    expect(second.length).toBe(1);
    expect(second[0].request.body).toEqual({ ...SUBSCRIBER, firstName: 'Claire' });

    second[0].flush(
      draftView({
        subscriber: { ...SUBSCRIBER, firstName: 'Claire' },
        status: 'SUBSCRIBER_CAPTURED',
      }),
    );

    expect(store.subscriber()?.firstName).toBe('Claire');
  });

  it('serialises concurrent saveSubscriber + saveSimulation on the same draftId', () => {
    store.start(PRODUCT, DRAFT_ID, KEY);

    service.saveSubscriber(SUBSCRIBER).subscribe();
    service.saveSimulation(SIMULATION).subscribe();

    // Replaces the former synchronized (session): only the first mutation is in flight.
    expect(httpMock.match(`/api/v1/drafts/${DRAFT_ID}/simulation`).length).toBe(0);
    const subscriber = httpMock.match(`/api/v1/drafts/${DRAFT_ID}/subscriber`);
    expect(subscriber.length).toBe(1);
    expect(service.subscriberInFlight()).toBe(true);
    expect(service.simulationInFlight()).toBe(true);

    subscriber[0].flush(draftView({ subscriber: SUBSCRIBER, status: 'SUBSCRIBER_CAPTURED' }));
    expect(service.subscriberInFlight()).toBe(false);

    const simulation = httpMock.match(`/api/v1/drafts/${DRAFT_ID}/simulation`);
    expect(simulation.length).toBe(1);
    simulation[0].flush(
      draftView({
        subscriber: SUBSCRIBER,
        simulation: { ...SIMULATION, contributed: '13000.00', finalCapital: '14000.00' },
        status: 'SIMULATED',
      }),
    );

    expect(service.simulationInFlight()).toBe(false);
    expect(store.hasSimulation()).toBe(true);
  });

  it('a failed mutation is reported to its caller and does not block the queue', () => {
    store.start(PRODUCT, DRAFT_ID, KEY);

    let status: number | undefined;
    service.saveSubscriber(SUBSCRIBER).subscribe({
      error: (error: { status: number }) => (status = error.status),
    });

    httpMock
      .expectOne(`/api/v1/drafts/${DRAFT_ID}/subscriber`)
      .flush({ status: 400, title: 'Bad Request' }, { status: 400, statusText: 'Bad Request' });

    expect(status).toBe(400);

    service.saveSimulation(SIMULATION).subscribe();
    httpMock
      .expectOne(`/api/v1/drafts/${DRAFT_ID}/simulation`)
      .flush(draftView({ subscriber: SUBSCRIBER, status: 'SIMULATED' }));
  });

  describe('reload(): getDraft 404/410 is expiry, not a technical error', () => {
    for (const status of [404, 410]) {
      it(`clears the store and redirects to /catalogue?notice=parcours on ${status}`, () => {
        const router = TestBed.inject(Router);
        const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
        store.start(PRODUCT, DRAFT_ID, KEY);

        let errored = false;
        service.reload(DRAFT_ID).subscribe({ error: () => (errored = true) });

        httpMock
          .expectOne(`/api/v1/drafts/${DRAFT_ID}`)
          .flush({ status, title: 'Gone' }, { status, statusText: 'Gone' });

        expect(errored).toBe(false);
        expect(store.hasDraft()).toBe(false);
        expect(store.draftId()).toBeNull();
        expect(readRawDraftRef()).toBeNull();
        expect(navigate).toHaveBeenCalledWith('/catalogue?notice=parcours');
      });
    }

    it('still reports a server failure as an error', () => {
      store.start(PRODUCT, DRAFT_ID, KEY);

      let status: number | undefined;
      service.reload(DRAFT_ID).subscribe({
        error: (error: { status: number }) => (status = error.status),
      });

      httpMock
        .expectOne(`/api/v1/drafts/${DRAFT_ID}`)
        .flush({ status: 500, title: 'Boom' }, { status: 500, statusText: 'Server Error' });

      expect(status).toBe(500);
      expect(store.draftId()).toBe(DRAFT_ID);
    });

    it('applies the draft returned by the gateway', () => {
      store.start(PRODUCT, DRAFT_ID, KEY);

      service.reload(DRAFT_ID).subscribe();
      httpMock
        .expectOne(`/api/v1/drafts/${DRAFT_ID}`)
        .flush(draftView({ subscriber: SUBSCRIBER, status: 'SUBSCRIBER_CAPTURED' }));

      expect(store.subscriber()).toEqual(SUBSCRIBER);
    });
  });
});
