package fr.demo.souscription.gateway.api.dto;

/**
 * Server-side subscription draft replacing the legacy server-side conversational state, mirroring
 * {@code global/contracts/shared-schemas/DraftView.json}.
 *
 * <p>{@code subscriber} is null until wizard step 2 completes and {@code simulation} until step 3
 * completes; {@code confirmationKey} is the idempotency key of the confirmation.
 */
public record DraftView(String draftId, ProductDetail product, SubscriberInput subscriber,
                        SimulationResult simulation, String confirmationKey, String status) {
}
