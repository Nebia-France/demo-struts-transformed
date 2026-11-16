package fr.demo.souscription.gateway.client;

import fr.demo.souscription.gateway.api.dto.SubscriptionConfirmation;

/**
 * Result of a confirmation call, carrying whether the subscription was created by this call
 * ({@code 201}) or whether the call was a replay of an already-confirmed key ({@code 200}).
 */
public record ConfirmationOutcome(boolean created, SubscriptionConfirmation subscription) {
}
