import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TIMEOUT_MS, timeoutInterceptor } from './timeout.interceptor';

describe('timeoutInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([timeoutInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('enforces a 10 000 ms timeout and issues exactly one request', () => {
    expect(TIMEOUT_MS).toBe(10_000);

    let error: unknown;
    http.get('/api/v1/products').subscribe({ error: (e: unknown) => (error = e) });

    const pending = httpMock.match('/api/v1/products');
    expect(pending.length).toBe(1);

    vi.advanceTimersByTime(TIMEOUT_MS - 1);
    expect(error).toBeUndefined();

    vi.advanceTimersByTime(1);
    expect((error as { name?: string })?.name).toBe('TimeoutError');

    // No retry: the interceptor never replays the request after the timeout.
    expect(httpMock.match('/api/v1/products').length).toBe(0);
    expect(pending[0].cancelled).toBe(true);
    httpMock.verify();
  });
});
