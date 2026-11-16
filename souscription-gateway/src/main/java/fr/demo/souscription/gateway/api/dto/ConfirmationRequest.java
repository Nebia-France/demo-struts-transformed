package fr.demo.souscription.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Confirmation of a complete draft. The {@code confirmationKey} makes the operation idempotent.
 * Mirrors {@code global/contracts/shared-schemas/ConfirmationRequest.json}: the key must equal the
 * one currently carried by the draft.
 *
 * <p>Only the presence and the length of the key are checked here; the comparison with the draft's
 * current key is authoritative in souscription-service.
 */
public record ConfirmationRequest(
        @NotBlank
        @Size(max = 36)
        String confirmationKey) {
}
