package fr.demo.souscription.api.dto;

import java.util.List;

/**
 * Corps d'erreur structure du contrat gele {@code FieldErrors.json}, media type
 * {@code application/problem+json} (RFC 7807 etendu d'un tableau d'erreurs par champ).
 *
 * <p>Remplace les {@code ActionErrors} Struts et la {@code Map<String, String>} de
 * {@code service/InputValidator}. L'ordre du tableau {@code errors} fait partie du contrat
 * (cf. {@code ERROR_VOCABULARY.md}).
 *
 * <p>Declare ici pour que le document OpenAPI publie reference le schema {@code FieldErrors} des
 * reponses 400 / 404 / 409 / 500 / 503 sans {@code $ref} pendant.
 */
public record FieldErrors(String type,
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
