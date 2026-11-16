package fr.demo.souscription.simulation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * RFC 7807 problem detail, structurellement identique à
 * global/contracts/shared-schemas/FieldErrors.json (média type application/problem+json).
 * Remplace les ActionErrors Struts et les clés ApplicationResources.
 */
@Schema(name = "FieldErrors",
        description = "RFC 7807 Problem Details extended with a field-level error array."
                + " Media type application/problem+json.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldErrors(
        @Schema(required = true, format = "uri", example = "about:blank") String type,
        @Schema(required = true) String title,
        @Schema(required = true, minimum = "400", maximum = "599") int status,
        @Schema String detail,
        @Schema(description = "Value of the X-Correlation-Id header handled for this request.")
        String correlationId,
        @Schema(description = "One entry per rejected field, in declaration order."
                + " Absent for non-validation problems.")
        List<FieldError> errors) {

    @Schema(name = "FieldError")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldError(
            @Schema(required = true, example = "initialAmount") String field,
            @Schema(required = true,
                    description = "Human-readable French message, identical in meaning to the"
                            + " legacy InputValidator message.") String message,
            @Schema(description = "Stable machine-readable rule identifier.") String code) {
    }
}
