/**
 * W7-T05: W3C Trace Context identifiers generated client-side.
 *
 * The source tier produced no client-side correlation at all: a gateway access log entry could
 * not be joined with the call that produced it. Every outgoing `/api/v1` call now carries a
 * `traceparent` header of the form `00-<32 hex trace-id>-<16 hex span-id>-01`, so the gateway
 * trace and the client-side evidence share a trace id.
 *
 * The identifiers are random bytes from `crypto.getRandomValues` only: no session value, no CSRF
 * token, no subscriber data ever enters a trace id (a trace id travels to the collector and to
 * the gateway logs, which are not a place for either).
 */

/** Trace id of the call currently in flight, exposed to the telemetry payload builder. */
let currentTrace: string | null = null;

/**
 * Builds one `traceparent` value and records its trace id in the request-scoped holder.
 * The sampled flag is `01`: the gateway decides what it keeps, the client never samples away
 * evidence it is the only producer of.
 */
export function newTraceparent(): string {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  let hex = '';
  for (const byte of bytes) {
    hex += (byte | 0x100).toString(16).slice(1);
  }
  currentTrace = hex.slice(0, 32);
  return `00-${currentTrace}-${hex.slice(32)}-01`;
}

/** Trace id of the most recent outgoing call, or null when no call has been made yet. */
export function currentTraceId(): string | null {
  return currentTrace;
}

/** Clears the holder, so a spec can assert on a clean state. */
export function resetTraceId(): void {
  currentTrace = null;
}
