import type { FieldErrors } from '../../api/models';

/**
 * W5-T02: the five error families the SPA knows about.
 *
 * Every HTTP failure leaving `errorInterceptor` is one of these, so no consumer inspects an
 * `HttpErrorResponse` or a status code again. The families mirror the observable behaviour of
 * the legacy tier: a validation refusal redisplayed the JSP with `${errors.<field>}`, a dropped
 * session sent the user back to the catalogue, a confirmation-key mismatch answered 409, and
 * everything else forwarded to the `erreur` view.
 */
export type AppError =
  /** Validation refusal: reprojected onto the form, never routed to an error page. */
  | { kind: 'field'; fields: FieldErrors }
  /** The server draft is gone (former `<session-timeout>20</session-timeout>`). */
  | { kind: 'expired' }
  /** Confirmation already handled for this key (former 409 of `confirmer`). */
  | { kind: 'conflict' }
  /** Transport failure: offline, DNS, CORS or aborted request. Retryable in place. */
  | { kind: 'network' }
  /** Anything else: rendered by `/erreur/:status`, with no technical detail exposed. */
  | { kind: 'http'; status: number; detail?: string };

/** Type guard for the error objects produced by `errorInterceptor`. */
export function isAppError(error: unknown): error is AppError {
  return (
    typeof error === 'object' &&
    error !== null &&
    typeof (error as { kind?: unknown }).kind === 'string'
  );
}

/** Route of the `http` family, the single surviving target of the 7 `<error-page>` mappings. */
export function errorPageUrl(status: number): string {
  return `/erreur/${status}`;
}

/**
 * Navigation target of a failure: only the `http` family leaves the current screen. `field`,
 * `network` and `conflict` are rendered in place so the user's input survives, and `expired` is
 * routed by `DraftSyncService` after it has cleared the store.
 */
export function errorPageUrlFor(error: unknown): string | null {
  return isAppError(error) && error.kind === 'http' ? errorPageUrl(error.status) : null;
}
