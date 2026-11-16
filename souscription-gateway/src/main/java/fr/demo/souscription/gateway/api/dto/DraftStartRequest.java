package fr.demo.souscription.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/drafts} (wizard step 1), declared inline in the public contract.
 *
 * <p>Structural constraints only (presence, length, shape); whether the code designates an existing
 * product is decided by souscription-catalogue-service.
 */
public record DraftStartRequest(
        @NotBlank
        @Size(max = 24)
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,23}$")
        String productCode) {
}
