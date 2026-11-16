package fr.demo.souscription.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Corps de requete de {@code POST /v1/drafts/{draftId}/confirmation}, aligne sur le schema gele
 * ConfirmationRequest.json : {@code additionalProperties: false} et un seul champ requis,
 * {@code confirmationKey} (uuid).
 *
 * <p>La case a cocher {@code accepted} du formulaire source n'est pas portee par le contrat gele :
 * un corps dont la cle est absente ou n'est pas un uuid est refuse en <b>400</b> (violation de
 * contrainte), ce qui est l'equivalent cible du refus source {@code accepted=false}. L'affichage
 * de la case appartient a souscription-web.
 */
public record ConfirmationRequest(@NotNull UUID confirmationKey) {
}
