package fr.demo.souscription.gateway.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Amounts and duration supplied by the subscriber at wizard step 3, mirroring
 * {@code global/contracts/shared-schemas/SimulationInput.json}. The annual rate is never supplied
 * by the client.
 */
public record SimulationInput(@Schema(type = SchemaType.STRING,
                                      pattern = "^[0-9]{1,6}\\.[0-9]{2}$",
                                      examples = "1000.00")
                              @JsonSerialize(using = ScaledDecimalSerializer.Amount.class)
                              @NotNull
                              BigDecimal initialAmount,
                              @Schema(type = SchemaType.STRING, pattern = "^[0-9]{1,6}\\.[0-9]{2}$",
                                      examples = "1000.00")
                              @JsonSerialize(using = ScaledDecimalSerializer.Amount.class)
                              @NotNull
                              BigDecimal monthlyAmount,
                              int durationYears) {
}
