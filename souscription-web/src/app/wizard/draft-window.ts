/**
 * Single definition of the draft validity window.
 *
 * The legacy tier expressed it as `<session-timeout>20</session-timeout>` in
 * `src/main/webapp/WEB-INF/web.xml`: past 20 minutes the session was dropped and the user was
 * sent back to the catalogue. The target reproduces the same observable behaviour from the
 * client store, and the value must stay aligned with the purge window of the server-side draft
 * table owned by `souscription-service` (see `README.md`).
 */
export const DRAFT_WINDOW_MS = 20 * 60 * 1000; // equivalent of web.xml <session-timeout>20</session-timeout>

export function isExpired(createdAt: number, now = Date.now()): boolean {
  return now - createdAt >= DRAFT_WINDOW_MS;
}

/**
 * Where an expired journey lands, exactly like the legacy `restart()` after a session timeout.
 * Expiry is never surfaced as a technical error.
 */
export const EXPIRY_REDIRECT_URL = '/catalogue?notice=parcours';

/** `getDraft` statuses that mean "the server draft is gone", i.e. expiry, not a failure. */
export function isExpiryStatus(status: number): boolean {
  return status === 404 || status === 410;
}
