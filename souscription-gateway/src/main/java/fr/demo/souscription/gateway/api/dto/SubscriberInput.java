package fr.demo.souscription.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Subscriber identity captured at wizard step 2, mirroring
 * {@code global/contracts/shared-schemas/SubscriberInput.json}.
 *
 * <p>{@code birthDate} is an ISO-8601 calendar date on the wire ({@code yyyy-MM-dd}); the
 * localised {@code fr_FR} day-first rendering of the source is a souscription-web display concern.
 * Authoritative validation stays in souscription-service: the constraints below are structural
 * (presence, length) and carry no business rule such as the minimum age.
 */
public record SubscriberInput(
        @NotBlank @Size(max = 80) String firstName,
        @NotBlank @Size(max = 80) String lastName,
        @NotBlank @Size(max = 254) String email,
        @NotNull LocalDate birthDate) {
}
