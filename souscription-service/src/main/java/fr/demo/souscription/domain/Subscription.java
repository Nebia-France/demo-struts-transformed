package fr.demo.souscription.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Souscription confirmee, modele de domaine immuable (W2-T02).
 *
 * <p>{@code reference} et {@code confirmationKey} sont des UUID et non des chaines, comme les
 * colonnes {@code uuid} de V1__subscriptions.sql. {@code confirmationKey} porte la confirmation
 * idempotente. {@code getCreatedAtLabel()} n'est pas porte : le formatage Europe/Paris est une
 * preoccupation de presentation detenue par souscription-web.
 */
public record Subscription(UUID reference, UUID confirmationKey, Product product,
                           Subscriber subscriber, Simulation simulation, Instant createdAt) {
}
