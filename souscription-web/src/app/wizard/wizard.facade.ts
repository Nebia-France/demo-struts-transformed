import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import type { SimulationRequest } from '../api/simulation-api.service';
import { SimulationApiService } from '../api/simulation-api.service';
import { SubscriptionApiService } from '../api/subscription-api.service';
import type {
  ConfirmationRequest,
  DraftView,
  SimulationInput,
  SimulationResult,
  SubscriberInput,
  SubscriptionConfirmation,
} from '../api/models';

/**
 * Typed replacement for the legacy `SubscriptionAction`, which was resolved by class name
 * from the legacy front-controller configuration and obtained its collaborators from the
 * static locator
 * `Application.get(getServlet().getServletContext())`.
 *
 * Both collaborators are injected by class token. The facade is stateless: the wizard
 * state lives in the signal store, and the authoritative draft lives server-side.
 */
@Injectable({ providedIn: 'root' })
export class WizardFacade {
  private readonly subscriptions = inject(SubscriptionApiService);
  private readonly simulations = inject(SimulationApiService);

  /** Former the legacy `commencer` action: opens a server-side draft for a product. */
  start(productCode: string): Observable<DraftView> {
    return this.subscriptions.startDraft(productCode);
  }

  /** Reloads the authoritative draft (former `session.getAttribute("draft")`). */
  draft(draftId: string): Observable<DraftView> {
    return this.subscriptions.getDraft(draftId);
  }

  /** Former POST on the legacy `souscripteur` action. */
  saveSubscriber(draftId: string, subscriber: SubscriberInput): Observable<DraftView> {
    return this.subscriptions.putDraftSubscriber(draftId, subscriber);
  }

  /** Former the legacy `simulation` action with command=calculer: projection only, nothing persisted. */
  simulate(input: SimulationRequest): Observable<SimulationResult> {
    return this.simulations.simulate(input);
  }

  /** Former the legacy `simulation` action with command=continuer: attaches the amounts to the draft. */
  saveSimulation(draftId: string, simulation: SimulationInput): Observable<DraftView> {
    return this.subscriptions.putDraftSimulation(draftId, simulation);
  }

  /** Former the legacy `confirmer` action: idempotent through the draft confirmationKey. */
  confirm(draftId: string, body: ConfirmationRequest): Observable<SubscriptionConfirmation> {
    return this.subscriptions.confirmDraft(draftId, body);
  }

  /** Former the legacy `confirmation` action: re-reads a confirmed subscription by reference. */
  subscription(reference: string): Observable<SubscriptionConfirmation> {
    return this.subscriptions.getSubscription(reference);
  }
}
