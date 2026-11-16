package fr.demo.souscription.api.dto;

import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Vue du brouillon serveur, alignee sur le schema gele DraftView.json.
 *
 * <p>Le schema declare {@code additionalProperties: false} : aucun champ supplementaire n'est
 * autorise. En particulier {@code expiresAt} n'est pas expose, la fenetre de retention ne
 * fuitant pas vers le client.
 *
 * <p>{@code status} prend l'une des valeurs
 * {@code STARTED | SUBSCRIBER_CAPTURED | SIMULATED | CONFIRMED}.
 */
public record DraftView(UUID draftId, ProductDetail product, SubscriberInput subscriber,
                        SimulationResult simulation, UUID confirmationKey,
                        @Schema(enumeration = {"STARTED", "SUBSCRIBER_CAPTURED", "SIMULATED",
                                "CONFIRMED"}) String status) {
}
