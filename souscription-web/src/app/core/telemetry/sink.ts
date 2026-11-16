/**
 * W7-T04: transport of the telemetry layer.
 *
 * `navigator.sendBeacon` is used first: it survives the unload of the page, which is the only
 * way an error raised while the user leaves the journey is still reported. Browsers without it
 * fall back to `fetch(..., { keepalive: true })`. Nothing is retried: a lost telemetry payload
 * must never cost a second request on the user's journey (`scripts/check-no-retry.mjs`).
 */
import { telemetryConfig } from './config';

/** Kinds of signal the collector receives, one per source of client-side evidence. */
export type TelemetryKind = 'error' | 'vital' | 'journey';

/**
 * A payload the collector accepts. Only technical fields: the route pattern, the release and a
 * sanitized message or a numeric measure. No subscriber data, no request body, no CSRF token
 * (enforced by `scripts/check-telemetry-privacy.mjs`).
 */
export interface TelemetryPayload {
  readonly kind: TelemetryKind;
  /** Sanitized error text, Web Vital name or journey event name. */
  readonly name: string;
  /** Numeric value of a Web Vital; absent for the other kinds. */
  readonly value?: number;
  /** Current router URL, the join key with the gateway access logs. */
  readonly route: string;
  /** Deployed version, read from `/version.json` at bootstrap. */
  readonly release: string;
  /**
   * W7-T05: trace id of the most recent API call, so a client error is joinable with the
   * gateway trace of the failing call. Random bytes only; absent when no call was made.
   */
  readonly traceId?: string;
}

/** Serializes and emits one payload. Returns false when telemetry is disabled or unavailable. */
export function send(payload: TelemetryPayload): boolean {
  const { enabled, collectorUrl } = telemetryConfig();
  if (!enabled) {
    return false;
  }
  const body = JSON.stringify(payload);
  if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
    return navigator.sendBeacon(collectorUrl, body);
  }
  if (typeof fetch === 'function') {
    void fetch(collectorUrl, {
      method: 'POST',
      keepalive: true,
      headers: { 'Content-Type': 'application/json' },
      body,
    }).catch(() => undefined);
    return true;
  }
  return false;
}
