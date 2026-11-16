import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { SimulationInput, SubscriberInput } from './models';
import { SubscriptionApiService } from './subscription-api.service';

describe('SubscriptionApiService', () => {
  let service: SubscriptionApiService;
  let httpMock: HttpTestingController;

  const draftId = '7f1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d';
  const subscriber: SubscriberInput = {
    firstName: 'Camille',
    lastName: 'Durand',
    email: 'camille.durand@example.com',
    birthDate: '1985-03-14',
  };
  const simulation: SimulationInput = {
    initialAmount: '1000.00',
    monthlyAmount: '100.00',
    durationYears: 10,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(SubscriptionApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a new draft', () => {
    service.startDraft('PRUDENT').subscribe();
    const req = httpMock.expectOne('/api/v1/drafts');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ productCode: 'PRUDENT' });
    req.flush(null);
  });

  it('GETs a draft', () => {
    service.getDraft(draftId).subscribe();
    const req = httpMock.expectOne(`/api/v1/drafts/${draftId}`);
    expect(req.request.method).toBe('GET');
    req.flush(null);
  });

  it('PUTs the subscriber', () => {
    service.putDraftSubscriber(draftId, subscriber).subscribe();
    const req = httpMock.expectOne(`/api/v1/drafts/${draftId}/subscriber`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(subscriber);
    req.flush(null);
  });

  it('PUTs the simulation', () => {
    service.putDraftSimulation(draftId, simulation).subscribe();
    const req = httpMock.expectOne(`/api/v1/drafts/${draftId}/simulation`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(simulation);
    req.flush(null);
  });

  it('POSTs the confirmation with the idempotency key', () => {
    const confirmationKey = '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d';
    service.confirmDraft(draftId, { confirmationKey }).subscribe();
    const req = httpMock.expectOne(`/api/v1/drafts/${draftId}/confirmation`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ confirmationKey });
    req.flush(null);
  });

  it('GETs a subscription', () => {
    service.getSubscription('SUB-2024-000001').subscribe();
    const req = httpMock.expectOne('/api/v1/subscriptions/SUB-2024-000001');
    expect(req.request.method).toBe('GET');
    req.flush(null);
  });

  it('encodes interpolated path segments', () => {
    service.getDraft('a/b').subscribe({ error: () => undefined });
    httpMock
      .expectOne('/api/v1/drafts/a%2Fb')
      .flush(null, { status: 404, statusText: 'Not Found' });

    service.getSubscription('a b/c').subscribe({ error: () => undefined });
    httpMock
      .expectOne('/api/v1/subscriptions/a%20b%2Fc')
      .flush(null, { status: 404, statusText: 'Not Found' });
  });

  it('propagates conflict errors', () => {
    let error: HttpErrorResponse | undefined;
    service
      .confirmDraft(draftId, { confirmationKey: 'stale' })
      .subscribe({ error: (e: HttpErrorResponse) => (error = e) });

    httpMock.expectOne(`/api/v1/drafts/${draftId}/confirmation`).flush(
      { type: 'about:blank', title: 'Conflict', status: 409 },
      { status: 409, statusText: 'Conflict' },
    );

    expect(error?.status).toBe(409);
  });
});
