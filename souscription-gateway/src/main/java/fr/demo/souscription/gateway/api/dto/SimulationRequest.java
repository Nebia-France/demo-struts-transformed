package fr.demo.souscription.gateway.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Body of {@code POST /api/v1/simulations}, declared inline in the public contract: a product code
 * plus the fields of {@link SimulationInput}. The client never supplies an annual rate.
 */
public record SimulationRequest(@NotBlank
                                @Size(max = 24)
                                @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,23}$")
                                String productCode,
                                @Schema(type = SchemaType.STRING,
                                        pattern = "^[0-9]{1,6}\\.[0-9]{2}$",
                                        examples = "1000.00")
                                @JsonSerialize(using = ScaledDecimalSerializer.Amount.class)
                                @NotNull
                                BigDecimal initialAmount,
                                @Schema(type = SchemaType.STRING,
                                        pattern = "^[0-9]{1,6}\\.[0-9]{2}$",
                                        examples = "1000.00")
                                @JsonSerialize(using = ScaledDecimalSerializer.Amount.class)
                                @NotNull
                                BigDecimal monthlyAmount,
                                int durationYears) {

    public SimulationInput toInput() {
        return new SimulationInput(initialAmount, monthlyAmount, durationYears);
    }
}
