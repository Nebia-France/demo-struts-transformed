import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-base-url';
import type {
  ConfirmationRequest,
  DraftView,
  SimulationInput,
  SubscriberInput,
  SubscriptionConfirmation,
} from './models';

/**
 * Draft and subscription operations of the gateway contract, one method per operationId.
 * The server-side draft replaces the legacy HttpSession wizard state; this service keeps
 * no copy of it.
 */
@Injectable({ providedIn: 'root' })
export class SubscriptionApiService {
  private readonly http = inject(HttpClient);

  startDraft(productCode: string): Observable<DraftView> {
    return this.http.post<DraftView>(`${API_BASE_URL}/drafts`, { productCode });
  }

  getDraft(draftId: string): Observable<DraftView> {
    return this.http.get<DraftView>(`${API_BASE_URL}/drafts/${encodeURIComponent(draftId)}`);
  }

  putDraftSubscriber(draftId: string, body: SubscriberInput): Observable<DraftView> {
    return this.http.put<DraftView>(
      `${API_BASE_URL}/drafts/${encodeURIComponent(draftId)}/subscriber`,
      body,
    );
  }

  putDraftSimulation(draftId: string, body: SimulationInput): Observable<DraftView> {
    return this.http.put<DraftView>(
      `${API_BASE_URL}/drafts/${encodeURIComponent(draftId)}/simulation`,
      body,
    );
  }

  confirmDraft(draftId: string, body: ConfirmationRequest): Observable<SubscriptionConfirmation> {
    return this.http.post<SubscriptionConfirmation>(
      `${API_BASE_URL}/drafts/${encodeURIComponent(draftId)}/confirmation`,
      body,
    );
  }

  getSubscription(reference: string): Observable<SubscriptionConfirmation> {
    return this.http.get<SubscriptionConfirmation>(
      `${API_BASE_URL}/subscriptions/${encodeURIComponent(reference)}`,
    );
  }
}
