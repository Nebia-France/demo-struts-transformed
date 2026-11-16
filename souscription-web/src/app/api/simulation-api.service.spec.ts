import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { SimulationResult } from './models';
import { SimulationApiService, type SimulationRequest } from './simulation-api.service';

describe('SimulationApiService', () => {
  let service: SimulationApiService;
  let httpMock: HttpTestingController;

  const request: SimulationRequest = {
    productCode: 'PRUDENT',
    initialAmount: '1000.00',
    monthlyAmount: '100.00',
    durationYears: 10,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(SimulationApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs the simulation input and keeps the amounts as decimal strings', () => {
    const result: SimulationResult = {
      ...request,
      contributed: '13000.00',
      finalCapital: '15763.42',
    };
    let received: SimulationResult | undefined;
    service.simulate(request).subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/v1/simulations');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(request);
    req.flush(result);
    expect(received?.finalCapital).toBe('15763.42');
  });

  it('propagates validation errors', () => {
    let error: HttpErrorResponse | undefined;
    service.simulate(request).subscribe({ error: (e: HttpErrorResponse) => (error = e) });

    httpMock.expectOne('/api/v1/simulations').flush(
      { type: 'about:blank', title: 'Bad Request', status: 400 },
      { status: 400, statusText: 'Bad Request' },
    );

    expect(error?.status).toBe(400);
  });
});
