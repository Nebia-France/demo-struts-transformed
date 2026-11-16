import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { errorInterceptor } from '../core/http/error.interceptor';
import { Router, provideRouter } from '@angular/router';
import type { DraftView, SubscriptionConfirmation } from '../api/models';
import { CONFIRMATION_URL, CONFLICT_MESSAGE, ConfirmService } from './confirm.service';
import { DraftStore } from './draft.store';
import { clearDraftRef } from './draft-storage';

const DRAFT_ID = '7f1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d';
const KEY = '1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
const CONFIRM_URL = `/api/v1/drafts/${DRAFT_ID}/confirmation`;

const PRODUCT: DraftView['product'] = {
  code: 'EQUILIBRE',
  name: 'Horizon Equilibre',
  profile: 'Equilibre',
  description: 'Support equilibre.',
  annualRate: '0.040000',
};

const CONFIRMATION: SubscriptionConfirmation = {
  reference: 'SUB-2024-000123',
  product: {
    code: 'EQUILIBRE',
    name: 'Horizon Equilibre',
    profile: 'Equilibre',
    annualRate: '0.040000',
  },
  subscriber: {
    firstName: 'Camille',
    lastName: 'Durand',
    email: 'camille.durand@example.com',
    birthDate: '1985-03-14',
  },
  simulation: {
    initialAmount: '1000.00',
    monthlyAmount: '100.00',
    durationYears: 10,
    contributed: '13000.00',
    finalCapital: '14000.00',
  },
  createdAt: '2024-05-01T09:30:00Z',
};

describe('ConfirmService', () => {
  let service: ConfirmService;
  let store: DraftStore;
  let httpMock: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

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
    service = TestBed.inject(ConfirmService);
    store = TestBed.inject(DraftStore);
    httpMock = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    store.start(PRODUCT, DRAFT_ID, KEY);
  });

  afterEach(() => httpMock.verify());

  it('replays the draft confirmationKey and navigates with replaceUrl so Back cannot replay it', async () => {
    const confirmed = service.confirm();

    const request = httpMock.expectOne(CONFIRM_URL);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ confirmationKey: KEY });
    request.flush(CONFIRMATION);
    await confirmed;

    expect(store.completedReference()).toBe('SUB-2024-000123');
    expect(store.completedKey()).toBe(KEY);
    expect(store.hasDraft()).toBe(false);
    expect(navigate).toHaveBeenCalledWith([CONFIRMATION_URL], { replaceUrl: true });
  });

  it('a rapid double call issues exactly one confirmDraft request', async () => {
    const first = service.confirm();
    const second = service.confirm();

    const requests = httpMock.match(CONFIRM_URL);
    expect(requests.length).toBe(1);

    requests[0].flush(CONFIRMATION);
    await Promise.all([first, second]);

    expect(httpMock.match(CONFIRM_URL).length).toBe(0);
    expect(service.inFlight()).toBe(false);
  });

  it('inFlight is raised while the confirmation runs and lowered when it settles', async () => {
    const confirmed = service.confirm();
    expect(service.inFlight()).toBe(true);

    httpMock.expectOne(CONFIRM_URL).flush(CONFIRMATION);
    await confirmed;

    expect(service.inFlight()).toBe(false);
  });

  it('a second confirm with the same key redisplays the confirmation without a new request', async () => {
    const confirmed = service.confirm();
    httpMock.expectOne(CONFIRM_URL).flush(CONFIRMATION);
    await confirmed;

    await service.confirm();

    expect(httpMock.match(CONFIRM_URL).length).toBe(0);
    expect(store.completedReference()).toBe('SUB-2024-000123');
    expect(navigate).toHaveBeenCalledTimes(2);
    expect(navigate).toHaveBeenLastCalledWith([CONFIRMATION_URL], { replaceUrl: true });
  });

  it('an incorrect confirmation key is surfaced as a conflict, not as a technical error', async () => {
    const confirmed = service.confirm();

    httpMock
      .expectOne(CONFIRM_URL)
      .flush({ status: 409, title: 'Conflict' }, { status: 409, statusText: 'Conflict' });
    await confirmed;

    expect(service.conflict()).toBe(CONFLICT_MESSAGE);
    expect(store.completedReference()).toBeNull();
    expect(navigate).not.toHaveBeenCalled();
    expect(service.inFlight()).toBe(false);
  });

  it('propagates a server failure and leaves the draft confirmable again', async () => {
    const confirmed = service.confirm();

    httpMock
      .expectOne(CONFIRM_URL)
      .flush({ status: 500, title: 'Boom' }, { status: 500, statusText: 'Server Error' });

    await expect(confirmed).rejects.toMatchObject({ status: 500 });
    expect(service.inFlight()).toBe(false);
    expect(store.hasDraft()).toBe(true);
  });
});
