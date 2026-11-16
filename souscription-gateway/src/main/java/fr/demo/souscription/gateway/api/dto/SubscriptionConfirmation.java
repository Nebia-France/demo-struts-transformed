package fr.demo.souscription.gateway.api.dto;

import java.time.Instant;

/**
 * Confirmed subscription, mirroring
 * {@code global/contracts/shared-schemas/SubscriptionConfirmation.json}. Product fields are
 * denormalised at confirmation time.
 *
 * <p>{@code createdAt} is a UTC instant in ISO-8601; the localised Europe/Paris
 * rendering is a souscription-web display concern.
 */
public record SubscriptionConfirmation(String reference, ProductSummary product,
                                       SubscriberInput subscriber, SimulationResult simulation,
                                       Instant createdAt) {
}
