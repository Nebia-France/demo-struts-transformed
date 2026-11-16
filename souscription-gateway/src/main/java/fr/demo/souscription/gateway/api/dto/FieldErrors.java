package fr.demo.souscription.gateway.api.dto;

import java.util.List;

/**
 * RFC 7807 Problem Details extended with a field-level error array, replacing the Struts
 * {@code ActionErrors} and its localised message keys. Mirrors
 * {@code global/contracts/shared-schemas/FieldErrors.json} and is served as
 * {@code application/problem+json}. Propagated unchanged by the gateway.
 */
public record FieldErrors(String type, String title, int status, String detail, String instance,
                          String correlationId, List<FieldError> errors) {

    /** One entry per rejected field, in declaration order. */
    public record FieldError(String field, String message, String code) {
    }
}
