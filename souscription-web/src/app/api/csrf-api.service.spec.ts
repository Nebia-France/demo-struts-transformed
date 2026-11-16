import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CsrfApiService } from './csrf-api.service';

describe('CsrfApiService', () => {
  let service: CsrfApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CsrfApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs the CSRF token', () => {
    let token: string | undefined;
    service.issueToken().subscribe((r) => (token = r.token));

    const req = httpMock.expectOne('/api/v1/csrf-token');
    expect(req.request.method).toBe('GET');
    req.flush({ token: 'stub-csrf-token-0123456789abcdef0123456789' });

    expect(token).toBe('stub-csrf-token-0123456789abcdef0123456789');
  });

  it('propagates errors', () => {
    let error: HttpErrorResponse | undefined;
    service.issueToken().subscribe({ error: (e: HttpErrorResponse) => (error = e) });

    httpMock.expectOne('/api/v1/csrf-token').flush(
      { type: 'about:blank', title: 'Internal Server Error', status: 500 },
      { status: 500, statusText: 'Internal Server Error' },
    );

    expect(error?.status).toBe(500);
  });
});
