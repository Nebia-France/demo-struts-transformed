import { Injectable, computed, inject, signal } from '@angular/core';
import type { WritableSignal } from '@angular/core';
import { Router } from '@angular/router';
import { isAppError } from '../core/error/app-error';
import {
  EMPTY,
  Observable,
  ReplaySubject,
  Subject,
  catchError,
  concatMap,
  defer,
  finalize,
  tap,
  throwError,
} from 'rxjs';
import type { DraftView, SimulationInput, SubscriberInput } from '../api/models';
import { SubscriptionApiService } from '../api/subscription-api.service';
import { DraftStore } from './draft.store';
import { saveDraftRef } from './draft-storage';
import { EXPIRY_REDIRECT_URL } from './draft-window';
import { markStepClick } from '../core/telemetry/step-marks';

/**
 * Replaces the per-step session mutations of the legacy wizard
 * (`session.setAttribute("draft", ...)` on `/commencer`, then one mutation per step) with
 * calls to the persisted draft owned by `souscription-service` through the gateway.
 *
 * The server response is authoritative: the store is only ever fed from the returned
 * `DraftView`, never from client-invented fields. Mutations are serialised with `concatMap`
 * over a single queue, so two step submissions on the same `draftId` can never run in
 * parallel. No journey data lives in the browser, so the SPA needs no server affinity.
 */
@Injectable({ providedIn: 'root' })
export class DraftSyncService {
  private readonly api = inject(SubscriptionApiService);
  private readonly store = inject(DraftStore);
  private readonly router = inject(Router);

  private readonly queue = new Subject<() => Observable<DraftView>>();

  private readonly startPending = signal(0);
  private readonly subscriberPending = signal(0);
  private readonly simulationPending = signal(0);

  /** In-flight guard of the product start, bound by the submit control of the product page. */
  readonly startInFlight = computed(() => this.startPending() > 0);
  /** In-flight guard of wizard step 2. */
  readonly subscriberInFlight = computed(() => this.subscriberPending() > 0);
  /** In-flight guard of wizard step 3. */
  readonly simulationInFlight = computed(() => this.simulationPending() > 0);

  constructor() {
    this.queue.pipe(concatMap((task) => task())).subscribe();
  }

  /** Former `/commencer`: opens the server-side draft for a product. */
  start(productCode: string): Observable<DraftView> {
    // W7-T02: the click that opens the draft starts the transition to the `souscripteur` step.
    markStepClick('souscripteur');
    return this.enqueue(this.startPending, () =>
      this.api.startDraft(productCode).pipe(
        tap((view) => {
          this.store.start(view.product, view.draftId, view.confirmationKey);
          saveDraftRef(view.draftId, Date.now());
        }),
      ),
    );
  }

  /** Step 2 transition: persists the subscriber on the server draft. */
  saveSubscriber(input: SubscriberInput): Observable<DraftView> {
    return this.enqueue(this.subscriberPending, () =>
      this.api.putDraftSubscriber(this.requireDraftId(), input).pipe(tap((view) => this.store.apply(view))),
    );
  }

  /** Step 3 transition: persists the retained simulation on the server draft. */
  saveSimulation(input: SimulationInput): Observable<DraftView> {
    return this.enqueue(this.simulationPending, () =>
      this.api.putDraftSimulation(this.requireDraftId(), input).pipe(tap((view) => this.store.apply(view))),
    );
  }

  /**
   * Re-reads the authoritative draft (former `session.getAttribute("draft")`). A 404 or a 410
   * means the server purged the draft past its window: the journey is expired, so the store and
   * the stored reference are cleared and the user is sent back to `/catalogue?notice=parcours`.
   * Expiry is never surfaced as a technical error.
   */
  reload(draftId: string): Observable<DraftView> {
    return this.api.getDraft(draftId).pipe(
      tap((view) => this.store.apply(view)),
      catchError((error: unknown) => {
        if (!isAppError(error) || error.kind !== 'expired') {
          return throwError(() => error);
        }
        this.expire();
        return EMPTY;
      }),
    );
  }

  /**
   * The `expired` family is the only one that clears the store: the server draft is gone, so the
   * journey restarts from the catalogue exactly like the legacy session timeout did. Every other
   * failure leaves the store — and therefore the user's input — untouched.
   */
  private expire(): void {
    this.store.clear();
    void this.router.navigateByUrl(EXPIRY_REDIRECT_URL);
  }

  private requireDraftId(): string {
    const draftId = this.store.draftId();
    if (draftId === null) {
      throw new Error('No server-side draft: the journey must be started from a product.');
    }
    return draftId;
  }

  /**
   * Defers the call until the queue reaches it, so mutations are strictly sequential, and
   * relays its outcome to the caller that subscribed. `pending` is raised as soon as the
   * mutation is queued and lowered when it settles, so the step submit control stays disabled
   * for the whole round trip instead of relying on the former `synchronized (session)`.
   */
  private enqueue(
    pending: WritableSignal<number>,
    task: () => Observable<DraftView>,
  ): Observable<DraftView> {
    return defer(() => {
      const outcome = new ReplaySubject<DraftView>(1);
      pending.update((count) => count + 1);
      this.queue.next(() =>
        defer(task).pipe(
          tap({
            next: (view) => {
              outcome.next(view);
              outcome.complete();
            },
            error: (error: unknown) => {
              if (isAppError(error) && error.kind === 'expired') {
                this.expire();
              }
              outcome.error(error);
            },
          }),
          finalize(() => pending.update((count) => count - 1)),
          catchError(() => EMPTY),
        ),
      );
      return outcome.asObservable();
    });
  }
}
