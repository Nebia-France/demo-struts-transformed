import { HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { XSRF_CONFIG } from '../../app.config';
import { httpProviders } from '../http.providers';

/**
 * Double-submit cookie parity (contracts/security-policy.json): the built-in XSRF interceptor
 * of `provideHttpClient` echoes the `XSRF-TOKEN` cookie in the `X-CSRF-Token` header on
 * mutating calls, and leaves safe methods untouched. This replaces the session token that
 * `SecurityFilter.java` compared against the hidden field of the `fragments/csrf` fragment.
 */
describe('double-submit XSRF handling', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    document.cookie = `${XSRF_CONFIG.cookieName}=jeton-double-submit; path=/`;
    TestBed.configureTestingModule({
      providers: [httpProviders, provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    document.cookie = `${XSRF_CONFIG.cookieName}=; path=/; max-age=0`;
  });

  it('sends the X-CSRF-Token header on a POST when the cookie is present', () => {
    http.post('/api/v1/drafts', {}).subscribe();

    const req = httpMock.expectOne('/api/v1/drafts');
    expect(req.request.headers.get(XSRF_CONFIG.headerName)).toBe('jeton-double-submit');
    req.flush({});
  });

  it('sends the X-CSRF-Token header on a PUT when the cookie is present', () => {
    http.put('/api/v1/drafts/d1/subscriber', {}).subscribe();

    const req = httpMock.expectOne('/api/v1/drafts/d1/subscriber');
    expect(req.request.headers.get(XSRF_CONFIG.headerName)).toBe('jeton-double-submit');
    req.flush({});
  });

  it('does not add the header on a GET', () => {
    http.get('/api/v1/products').subscribe();

    const req = httpMock.expectOne('/api/v1/products');
    expect(req.request.headers.has(XSRF_CONFIG.headerName)).toBe(false);
    req.flush([]);
  });
});
