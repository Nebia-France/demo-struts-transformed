import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { isAppError } from '../core/error/app-error';
import { errorMessage } from '../core/error/error-catalog';
import { SubscriptionApiService } from '../api/subscription-api.service';
import { DraftStore } from './draft.store';

/** Where the legacy `confirmer` action redirect-after-post landed. */
export const CONFIRMATION_URL = '/souscription/confirmation';

/**
 * Functional outcome of the source 409: the draft carried another confirmation key, so the
 * request was already handled (or the subscriber changed after the simulation and the key was
 * renewed server-side). Classified as a conflict, never as a technical failure.
 */
export const CONFLICT_MESSAGE = 'Demande déjà traitée.';

/**
 * Replaces the legacy `confirmer` action, which compared `draft.getConfirmationKey()` with the
 * submitted key inside `synchronized (session)`, answered 409 on mismatch, then redirected after
 * POST so the Back button could not replay the confirmation.
 *
 * The three invariants are preserved without a session lock:
 * - one subscription per confirmation key: the key comes from the server draft and is replayed
 *   as-is, so the gateway returns the existing subscription instead of creating a second one;
 * - no replay through the Back button: the navigation uses `replaceUrl: true`;
 * - no double submission: `inFlight` short-circuits a second call and disables the submit
 *   control while the first one is running.
 */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private readonly api = inject(SubscriptionApiService);
  private readonly store = inject(DraftStore);
  private readonly router = inject(Router);

  private readonly inFlightState = signal(false);
  private readonly conflictState = signal<string | null>(null);
  private readonly retryableState = signal<string | null>(null);

  /** True while the confirmation request is running; the submit control binds to it. */
  readonly inFlight = this.inFlightState.asReadonly();
  /** Conflict message equivalent to the source 409, or null. */
  readonly conflict = this.conflictState.asReadonly();
  /**
   * W5-T05: message of a failure the summary step redisplays **in place**, with the entered
   * values and the ticked acceptance box preserved and a retry control — the 503 the source
   * answered when `SQLException` was caught, and the `network` family of the 10 s timeout.
   * Null when the last attempt did not fail that way.
   */
  readonly retryable = this.retryableState.asReadonly();

  async confirm(): Promise<void> {
    if (this.inFlightState()) {
      return;
    }
    if (this.store.completedReference() !== null) {
      // Same key already confirmed: redisplay the existing confirmation, never create a second
      // subscription and never issue a second request.
      await this.router.navigate([CONFIRMATION_URL], { replaceUrl: true });
      return;
    }
    const draftId = this.store.draftId();
    const confirmationKey = this.store.confirmationKey();
    if (draftId === null || confirmationKey === null) {
      return;
    }
    this.inFlightState.set(true);
    this.conflictState.set(null);
    this.retryableState.set(null);
    try {
      const result = await firstValueFrom(this.api.confirmDraft(draftId, { confirmationKey }));
      this.store.complete(result);
      await this.router.navigate([CONFIRMATION_URL], { replaceUrl: true });
    } catch (error: unknown) {
      if (isAppError(error) && error.kind === 'conflict') {
        this.conflictState.set(CONFLICT_MESSAGE);
        return;
      }
      const message = retryableMessage(error);
      if (message !== null) {
        this.retryableState.set(message);
        return;
      }
      throw error;
    } finally {
      this.inFlightState.set(false);
    }
  }
}

/**
 * Message of the two failures the summary step keeps in page, or null when the failure belongs
 * to the global handler: a 503 (the source's caught `SQLException`) and the `network` family,
 * which covers being offline and the 10 s timeout of the interceptor.
 */
function retryableMessage(error: unknown): string | null {
  if (!isAppError(error)) {
    return null;
  }
  if (error.kind === 'network') {
    return errorMessage('network');
  }
  return error.kind === 'http' && error.status === 503 ? errorMessage('503') : null;
}
