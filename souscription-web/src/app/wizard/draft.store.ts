import { Injectable, computed, signal } from '@angular/core';
import type {
  DraftView,
  ProductDetail,
  SimulationResult,
  SubscriberInput,
  SubscriptionConfirmation,
} from '../api/models';
import { clearDraftRef, saveDraftRef } from './draft-storage';
import { isExpired } from './draft-window';

/**
 * Client-side projection of the server-side draft. Replaces the `draft`, `completed`,
 * `completedReference`, `completedKey` session attributes of the legacy wizard.
 */
export interface DraftState {
  draftId: string;
  /** Epoch milliseconds; drives the TTL that replaces the session timeout. */
  createdAt: number;
  product: ProductDetail;
  subscriber: SubscriberInput | null;
  simulation: SimulationResult | null;
  confirmationKey: string;
  completed: boolean;
}

/**
 * Confirmed subscription kept after the draft is cleared: the former `completed`,
 * `completedReference` and `completedKey` session attributes.
 */
export interface DraftCompletion {
  reference: string;
  confirmationKey: string;
  subscription: SubscriptionConfirmation;
}

/**
 * The single store of the wizard. Step components never write server state directly:
 * they call the store, and the store is fed from the `DraftView` returned by the gateway.
 */
@Injectable({ providedIn: 'root' })
export class DraftStore {
  private readonly state = signal<DraftState | null>(null);
  private readonly completion = signal<DraftCompletion | null>(null);

  readonly draftId = computed(() => this.state()?.draftId ?? null);
  readonly product = computed(() => this.state()?.product ?? null);
  readonly subscriber = computed(() => this.state()?.subscriber ?? null);
  readonly simulation = computed(() => this.state()?.simulation ?? null);
  readonly confirmationKey = computed(() => this.state()?.confirmationKey ?? null);

  readonly hasDraft = computed(() => {
    const current = this.state();
    return current !== null && !current.completed && !this.isStale(current);
  });
  readonly hasSubscriber = computed(() => !!this.state()?.subscriber);
  readonly hasSimulation = computed(() => !!this.state()?.simulation);

  readonly completedReference = computed(() => this.completion()?.reference ?? null);
  /** Confirmed subscription displayed by the confirmation step, former `completed`. */
  readonly completed = computed(() => this.completion()?.subscription ?? null);
  readonly completedKey = computed(() => this.completion()?.confirmationKey ?? null);

  /** Former `commencer`: a freshly started draft becomes the current one. */
  start(product: ProductDetail, draftId: string, confirmationKey: string): void {
    const createdAt = Date.now();
    this.completion.set(null);
    this.state.set({
      draftId,
      createdAt,
      product,
      subscriber: null,
      simulation: null,
      confirmationKey,
      completed: false,
    });
    saveDraftRef(draftId, createdAt);
  }

  /**
   * Adopts the authoritative draft returned by the gateway, either after a step update or
   * after a bootstrap rehydration. `createdAt` is preserved across updates.
   */
  apply(view: DraftView, createdAt = this.state()?.createdAt ?? Date.now()): void {
    this.state.set({
      draftId: view.draftId,
      createdAt,
      product: view.product,
      subscriber: view.subscriber ?? null,
      simulation: view.simulation ?? null,
      confirmationKey: view.confirmationKey,
      completed: view.status === 'CONFIRMED',
    });
    saveDraftRef(view.draftId, createdAt);
  }

  /** Former `confirmer`: keeps the reference and the key for the confirmation screen. */
  complete(result: SubscriptionConfirmation): void {
    const confirmationKey = this.state()?.confirmationKey ?? '';
    this.completion.set({
      reference: result.reference,
      confirmationKey,
      subscription: result,
    });
    this.state.set(null);
    clearDraftRef();
  }

  clear(): void {
    this.state.set(null);
    this.completion.set(null);
    clearDraftRef();
  }

  /**
   * Drops an expired journey: the store is emptied and the stored draft reference is removed,
   * so a reload cannot resurrect a journey past the window. Returns true when it expired.
   */
  expireIfStale(): boolean {
    const current = this.state();
    if (current === null || !this.isStale(current)) {
      return false;
    }
    this.state.set(null);
    clearDraftRef();
    return true;
  }

  private isStale(current: DraftState): boolean {
    return isExpired(current.createdAt);
  }
}
