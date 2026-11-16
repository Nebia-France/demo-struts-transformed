package fr.demo.souscription.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Corps de requete de {@code POST /v1/drafts}, aligne sur le contrat gele
 * {@code souscription-gateway-to-souscription-service} : {@code additionalProperties: false} et
 * un seul champ requis, {@code productCode}.
 *
 * <p>Le taux annuel n'est jamais fourni par le client : il est lu au catalogue et copie sur le
 * brouillon par le service.
 */
public record StartDraftRequest(@NotBlank String productCode) {
}
