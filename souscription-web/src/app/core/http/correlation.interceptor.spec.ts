import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { correlationInterceptor } from './correlation.interceptor';
import { resetTraceId } from './traceparent';

/** `00-<32 hex trace-id>-<16 hex span-id>-01`, the only format the gateway accepts. */
const TRACEPARENT_PATTERN = /^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/;
import { resetTelemetryConfig, setTelemetryConfig } from '../telemetry/config';
import { TelemetryService } from '../telemetry/telemetry.service';

/**
 * W7-T05: 100% of the API calls must be joinable with a gateway trace, and a reported client
 * error must carry the trace id of the call that failed.
 */
describe('correlationInterceptor (W7-T05)', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let sent: string[];
  let original: typeof navigator.sendBeacon | undefined;

  beforeEach(() => {
    sent = [];
    original = navigator.sendBeacon;
    // jsdom does not implement sendBeacon, so the transport is installed rather than spied on.
    Object.defineProperty(navigator, 'sendBeacon', {
      configurable: true,
      writable: true,
      value: (_url: string, body?: BodyInit | null) => {
        sent.push(String(body));
        return true;
      },
    });
    resetTelemetryConfig();
    setTelemetryConfig({ release: 'abc1234', collectorUrl: '/telemetry/v1/events' });
    resetTraceId();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(withInterceptors([correlationInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    resetTelemetryConfig();
    resetTraceId();
    if (original) {
      Object.defineProperty(navigator, 'sendBeacon', {
        configurable: true,
        writable: true,
        value: original,
      });
    } else {
      delete (navigator as { sendBeacon?: unknown }).sendBeacon;
    }
  });

  it('adds a W3C traceparent header to every request', () => {
    http.get('/api/v1/products').subscribe();
    http.post('/api/v1/subscriptions', {}).subscribe();

    const requests = [
      httpMock.expectOne('/api/v1/products'),
      httpMock.expectOne('/api/v1/subscriptions'),
    ];
    for (const request of requests) {
      const header = request.request.headers.get('traceparent');
      expect(header).not.toBeNull();
      expect(header).toMatch(TRACEPARENT_PATTERN);
      request.flush({});
    }
    httpMock.verify();
  });

  it('gives two requests distinct span ids', () => {
    http.get('/api/v1/products').subscribe();
    http.get('/api/v1/products').subscribe();

    const headers = httpMock
      .match('/api/v1/products')
      .map((request) => request.request.headers.get('traceparent') ?? '');
    expect(headers).toHaveLength(2);
    const spanOf = (header: string): string => header.split('-')[2];
    expect(spanOf(headers[0])).not.toBe(spanOf(headers[1]));
    expect(headers[0]).not.toBe(headers[1]);
  });

  it('reports a failing call with the trace id of that call', () => {
    http.get('/api/v1/products').subscribe({ error: () => undefined });
    const request = httpMock.expectOne('/api/v1/products');
    const traceId = (request.request.headers.get('traceparent') ?? '').split('-')[1];
    request.flush({}, { status: 500, statusText: 'Server Error' });

    TestBed.inject(TelemetryService).reportError(new Error('boom'));

    expect(sent).toHaveLength(1);
    const payload = JSON.parse(sent[0]) as { traceId?: string };
    expect(payload.traceId).toBe(traceId);
  });

  it('never puts the CSRF token or personal data in the header', () => {
    http.get('/api/v1/products').subscribe();
    const request = httpMock.expectOne('/api/v1/products');
    const header = request.request.headers.get('traceparent') ?? '';
    // The value is hexadecimal random bytes only; nothing else can be encoded in it.
    expect(header).toMatch(TRACEPARENT_PATTERN);
    request.flush({});
  });
});
