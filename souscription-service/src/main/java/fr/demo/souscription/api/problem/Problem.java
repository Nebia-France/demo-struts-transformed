package fr.demo.souscription.api.problem;

import java.util.List;

/**
 * Corps {@code application/problem+json} produit par les {@code ExceptionMapper} du module
 * (W4-T04). La forme est celle du contrat gele {@code global/contracts/shared-schemas/
 * FieldErrors.json} : {@code type}, {@code title}, {@code status}, {@code detail},
 * {@code instance}, {@code correlationId} puis un tableau {@code errors} d'objets
 * {@code {field, message, code}} dans l'ordre de declaration des champs.
 *
 * <p>Remplace les pages d'erreur JSP et les appels {@code response.sendError(...)} du source :
 * aucune reponse d'erreur du service n'est rendue en HTML.
 *
 * <p>{@code correlationId} porte la valeur de l'en-tete {@code X-Correlation-Id} traitee pour la
 * requete : la valeur posee dans le MDC par {@code observability.CorrelationFilter} (W5-T01),
 * egale a l'en-tete recu lorsqu'il est present et a un UUIDv4 genere sinon.
 *
 * <p>Le corps ne contient jamais de {@code SQLState}, de trace d'appel ni de texte SQL.
 */
public record Problem(String type,
                      String title,
                      int status,
                      String detail,
                      String instance,
                      String correlationId,
                      List<FieldError> errors) {

    /** Une entree par champ rejete, dans l'ordre de declaration des champs. */
    public record FieldError(String field, String message, String code) {
    }
}
