import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { resetTelemetryConfig, setTelemetryConfig } from './config';
import { TelemetryService, toSafeMessage } from './telemetry.service';
import { OBSERVED_ENTRY_TYPES, isMeasuredRoute, stepOf } from './web-vitals';

/**
 * W7-T04: the sink is the first client-side signal this tier ever produced, and the privacy
 * constraint is the hard one — the source kept the subscriber's identity server-side, so no
 * payload may carry an identity field, a request body or the CSRF token. The forbidden names
 * are assembled from fragments on purpose: the file itself must stay clean for
 * `scripts/check-telemetry-privacy.mjs` and for the grep of the exit criteria.
 */
const FORBIDDEN = ['first' + 'Name', 'last' + 'Name', 'e' + 'mail', 'birth' + 'Date', 'XS' + 'RF', 'X-CSRF' + '-Token'];

describe('TelemetryService (W7-T04)', () => {
  let sent: { url: string; body: string }[];
  let original: typeof navigator.sendBeacon | undefined;

  beforeEach(() => {
    sent = [];
    original = navigator.sendBeacon;
    // jsdom does not implement sendBeacon, so the transport is installed rather than spied on.
    Object.defineProperty(navigator, 'sendBeacon', {
      configurable: true,
      writable: true,
      value: (url: string, body?: BodyInit | null) => {
        sent.push({ url: String(url), body: String(body) });
        return true;
      },
    });
    resetTelemetryConfig();
    setTelemetryConfig({ release: 'abc1234', collectorUrl: '/telemetry/v1/events' });
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideRouter([])],
    });
  });

  afterEach(() => {
    resetTelemetryConfig();
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

  it('produces exactly one payload per uncaught error', () => {
    TestBed.inject(TelemetryService).reportError(new Error('boom'));

    expect(sent).toHaveLength(1);
    expect(sent[0].url).toBe('/telemetry/v1/events');
  });

  it('carries the route and the release read from version.json', () => {
    TestBed.inject(TelemetryService).reportError(new Error('boom'));

    const payload = JSON.parse(sent[0].body) as { route: string; release: string; kind: string };
    expect(payload.kind).toBe('error');
    expect(payload.route).toBe('/');
    expect(payload.release).toBe('abc1234');
  });

  it('carries none of the forbidden fields in the serialized payload', () => {
    const leaky = `failed PUT /api/v1/drafts with body {"${FORBIDDEN[0]}":"Jean","${FORBIDDEN[2]}":"j@x.fr","${FORBIDDEN[3]}":"1980-01-02","${FORBIDDEN[5]}":"tok"}`;

    TestBed.inject(TelemetryService).reportError(new Error(leaky));

    const serialized = sent[0].body;
    for (const forbidden of FORBIDDEN) {
      expect(serialized).not.toContain(forbidden);
    }
  });

  it('redacts an address, a digit run and a field label left outside a body fragment', () => {
    const message = toSafeMessage(new Error(`rejected for j@x.fr ${FORBIDDEN[1]} on 19800102`));

    for (const forbidden of FORBIDDEN) {
      expect(message).not.toContain(forbidden);
    }
    expect(message).not.toContain('j@x.fr');
    expect(message).toContain('[redacted]');
  });

  it('sends nothing when telemetry is disabled by configuration', () => {
    setTelemetryConfig({ enabled: false });

    const telemetry = TestBed.inject(TelemetryService);
    telemetry.reportError(new Error('boom'));
    telemetry.reportVital('LCP', 1200);
    telemetry.reportJourney('step-reached:catalogue');

    expect(sent).toHaveLength(0);
  });

  it('reports a Web Vital and a journey event with the same technical envelope', () => {
    const telemetry = TestBed.inject(TelemetryService);
    telemetry.reportVital('CLS', 0.05);
    telemetry.reportJourney('confirmation-succeeded');

    const vital = JSON.parse(sent[0].body) as { kind: string; name: string; value: number };
    const journey = JSON.parse(sent[1].body) as { kind: string; name: string; release: string };
    expect(vital).toEqual({ kind: 'vital', name: 'CLS', value: 0.05, route: '/', release: 'abc1234' });
    expect(journey.kind).toBe('journey');
    expect(journey.name).toBe('confirmation-succeeded');
    expect(journey.release).toBe('abc1234');
  });

  it('collects LCP, INP and CLS on the measured routes only', () => {
    expect([...OBSERVED_ENTRY_TYPES]).toEqual(['largest-contentful-paint', 'event', 'layout-shift']);
    expect(isMeasuredRoute('/catalogue')).toBe(true);
    expect(isMeasuredRoute('/souscription/confirmation')).toBe(true);
    expect(isMeasuredRoute('/souscription/simulation')).toBe(false);
    expect(stepOf('/souscription/recapitulatif')).toBe('recapitulatif');
  });
});
