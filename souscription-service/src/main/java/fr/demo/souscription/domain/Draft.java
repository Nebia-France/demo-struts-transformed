package fr.demo.souscription.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Brouillon de parcours, modele de domaine immuable (W2-T02).
 *
 * <p>Remplace le {@code model/Draft} mutable porte par session HTTP de servlet : l'etat vit
 * desormais dans {@code subscriptions.subscription_draft}. {@code subscriber} et
 * {@code simulation} sont nuls tant que les etapes 2 et 3 du parcours ne sont pas franchies,
 * exactement comme le brouillon source.
 *
 * <p>Le source regenerait {@code confirmationKey} a chaque mutation ; l'equivalent immuable est
 * une nouvelle instance portant une nouvelle cle.
 */
public record Draft(UUID draftId, UUID confirmationKey, Product product,
                    Subscriber subscriber, Simulation simulation, Instant expiresAt) {

    /** Statut derive du parcours, aligne sur l'enumeration du schema gele DraftView. */
    public DraftStatus status() {
        if (simulation != null) {
            return DraftStatus.SIMULATED;
        }
        return subscriber != null ? DraftStatus.SUBSCRIBER_CAPTURED : DraftStatus.STARTED;
    }
}
