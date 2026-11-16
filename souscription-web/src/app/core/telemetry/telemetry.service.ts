import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { currentTraceId } from '../http/traceparent';
import { telemetryConfig } from './config';
import { send, type TelemetryPayload } from './sink';

/**
 * Fragments that could carry user input are removed before anything leaves the browser: an
 * object or array literal in a message is the usual way a request body ends up in a stack
 * trace, an `@` form is an address, and a run of digits is an amount, a date or an identifier.
 * The key names of the subscriber fields are redacted on top of that, so a message built by a
 * library from a form model cannot leak a label either.
 */
const BODY_FRAGMENT = /[{[][\s\S]*$/;
const ADDRESS = /[\w.+-]+@[\w.-]+/g;
const DIGIT_RUN = /\d{2,}/g;
const SUBSCRIBER_KEY = /(?:first|last)\s*-?_?name|e-?mail|birth\s*-?_?date|x-csrf-token|xsrf/gi;
const REDACTED = '[redacted]';
/** Upper bound of a reported message, so a long trace cannot smuggle data past the filters. */
const MAX_MESSAGE = 200;

/**
 * Builds the only text a payload carries. Never includes the request body, never includes a
 * subscriber identity field, never includes the CSRF token.
 */
export function toSafeMessage(error: unknown): string {
  const raw =
    error instanceof Error
      ? `${error.name}: ${error.message}`
      : typeof error === 'string'
        ? error
        : typeof error === 'object' && error !== null && 'kind' in error
          ? // The five families of `core/error/app-error.ts` are already technical labels.
            `app-error: ${String((error as { kind: unknown }).kind)}`
          : 'unknown error';
  return raw
    .replace(BODY_FRAGMENT, '')
    .replace(ADDRESS, REDACTED)
    .replace(DIGIT_RUN, REDACTED)
    .replace(SUBSCRIBER_KEY, REDACTED)
    .trim()
    .slice(0, MAX_MESSAGE);
}

/**
 * W7-T04: the sink every uncaught error, Web Vital and journey event is reported to.
 *
 * The source tier produced no client-side signal at all: a failure after the page had been
 * served was invisible to the operator. This service is the replacement — `GlobalErrorHandler`
 * and the `unhandledrejection` hook of `main.ts` report here, and each report produces exactly
 * one payload carrying the current route and the deployed release, with no personal data.
 */
@Injectable({ providedIn: 'root' })
export class TelemetryService {
  private readonly router = inject(Router);

  /** One payload per uncaught error, with the route and the release of the deployment. */
  reportError(error: unknown): void {
    this.emit({ kind: 'error', name: toSafeMessage(error) });
  }

  /** One payload per Web Vital sample (LCP, INP, CLS) observed on a measured route. */
  reportVital(name: string, value: number): void {
    this.emit({ kind: 'vital', name, value });
  }

  /** One payload per journey event (step reached, step abandoned, confirmation succeeded). */
  reportJourney(event: string): void {
    this.emit({ kind: 'journey', name: event });
  }

  private emit(partial: Pick<TelemetryPayload, 'kind' | 'name' | 'value'>): void {
    const payload: TelemetryPayload = {
      ...partial,
      route: this.router.url,
      release: telemetryConfig().release,
      // W7-T05: join key with the gateway trace of the call that was in flight. Dropped from
      // the serialized body when no call has been made yet.
      traceId: currentTraceId() ?? undefined,
    };
    send(payload);
  }
}
