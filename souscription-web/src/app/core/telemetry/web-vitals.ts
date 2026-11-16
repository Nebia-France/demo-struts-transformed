import { NavigationEnd, Router } from '@angular/router';
import type { TelemetryService } from './telemetry.service';

/**
 * W7-T06: Web Vitals and journey events.
 *
 * The source tier measured nothing on the client. The two routes whose field performance is a
 * stated objective are the landing route (`/catalogue`, whose LCP replaces the first paint of
 * the `catalogue` action) and the end of the journey (`/souscription/confirmation`), so the three
 * Core Web Vitals are sampled there: LCP from `largest-contentful-paint`, INP from the slowest
 * `event` entry, CLS from the sum of the non-input `layout-shift` entries.
 *
 * Journey events carry a step name and nothing else: the completion rate of the funnel is
 * readable without any subscriber data ever reaching the collector.
 */

/** Routes whose Web Vitals are reported; other routes are observed but not sampled. */
export const MEASURED_ROUTES = ['/catalogue', '/souscription/confirmation'] as const;

/** Entry types the collection relies on, asserted by the spec. */
export const OBSERVED_ENTRY_TYPES = ['largest-contentful-paint', 'event', 'layout-shift'] as const;

/** Journey event names, the three transitions the funnel is computed from. */
export const JOURNEY_EVENTS = {
  stepReached: (step: string) => `step-reached:${step}`,
  stepAbandoned: (step: string) => `step-abandoned:${step}`,
  confirmationSucceeded: 'confirmation-succeeded',
} as const;

const CONFIRMATION_ROUTE = '/souscription/confirmation';

/** True when `url` is one of the routes the vitals are reported for. */
export function isMeasuredRoute(url: string): boolean {
  const path = url.split('?')[0];
  return MEASURED_ROUTES.some((route) => path === route);
}

/** Last path segment of a route, used as the journey step name. */
export function stepOf(url: string): string {
  const path = url.split('?')[0];
  const segments = path.split('/').filter(Boolean);
  return segments[segments.length - 1] ?? 'catalogue';
}

interface LayoutShift extends PerformanceEntry {
  readonly value: number;
  readonly hadRecentInput: boolean;
}

function observe(type: string, handler: (entries: PerformanceEntryList) => void): void {
  if (typeof PerformanceObserver === 'undefined') {
    return;
  }
  const supported = PerformanceObserver.supportedEntryTypes as readonly string[] | undefined;
  if (supported && !supported.includes(type)) {
    return;
  }
  try {
    const observer = new PerformanceObserver((list) => handler(list.getEntries()));
    // `buffered` replays the entries emitted before this code ran, which is the only way the
    // LCP of the landing route is captured at all. `durationThreshold` is specific to the
    // `event` type and is not part of the DOM typings, hence the cast.
    const options = { type, buffered: true } as PerformanceObserverInit;
    if (type === 'event') {
      (options as { durationThreshold?: number }).durationThreshold = 40;
    }
    observer.observe(options);
  } catch {
    // An entry type the browser does not know must not break the journey.
  }
}

/**
 * Registers the Web Vitals observers and the journey listeners. Called once at bootstrap; the
 * observers stay for the lifetime of the document, and the route is re-read on every sample so
 * only the measured routes are reported.
 */
export function startWebVitals(telemetry: TelemetryService, router: Router): void {
  let lcp = 0;
  let inp = 0;
  let cls = 0;

  observe('largest-contentful-paint', (entries) => {
    for (const entry of entries) {
      lcp = Math.max(lcp, entry.startTime);
    }
  });
  observe('event', (entries) => {
    for (const entry of entries) {
      inp = Math.max(inp, entry.duration);
    }
  });
  observe('layout-shift', (entries) => {
    for (const entry of entries as LayoutShift[]) {
      if (!entry.hadRecentInput) {
        cls += entry.value;
      }
    }
  });

  /** Flushes the three vitals once, when the document is hidden or unloaded. */
  const flush = (): void => {
    if (!isMeasuredRoute(router.url)) {
      return;
    }
    telemetry.reportVital('LCP', lcp);
    telemetry.reportVital('INP', inp);
    telemetry.reportVital('CLS', cls);
  };

  if (typeof window !== 'undefined') {
    window.addEventListener('pagehide', () => {
      const step = stepOf(router.url);
      if (router.url.split('?')[0] !== CONFIRMATION_ROUTE) {
        telemetry.reportJourney(JOURNEY_EVENTS.stepAbandoned(step));
      }
      flush();
    });
    window.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden') {
        flush();
      }
    });
  }

  router.events.subscribe((event) => {
    if (!(event instanceof NavigationEnd)) {
      return;
    }
    const url = event.urlAfterRedirects;
    telemetry.reportJourney(JOURNEY_EVENTS.stepReached(stepOf(url)));
    if (url.split('?')[0] === CONFIRMATION_ROUTE) {
      telemetry.reportJourney(JOURNEY_EVENTS.confirmationSucceeded);
      flush();
    }
  });
}
