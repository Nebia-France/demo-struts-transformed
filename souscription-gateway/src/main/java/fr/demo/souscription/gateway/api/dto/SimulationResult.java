package fr.demo.souscription.gateway.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.math.BigDecimal;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Compound-interest projection, mirroring
 * {@code global/contracts/shared-schemas/SimulationResult.json}. Amounts travel as JSON strings
 * with exactly two decimals.
 */
public record SimulationResult(@Schema(type = SchemaType.STRING,
                                       pattern = "^[0-9]{1,6}\\.[0-9]{2}$",
                                       examples = "1000.00")
                               @JsonSerialize(using = ScaledDecimalSerializer.Amount.class)
                               BigDecimal initialAmount,
                               @Schema(type = SchemaType.STRING,
                                       pattern = "^[0-9]{1,6}\\.[0-9]{2}$",
                                       examples = "1000.00")
                               @JsonSerialize(using = ScaledDecimalSerializer.Amount.class)
                               BigDecimal monthlyAmount,
                               int durationYears,
                               @Schema(type = SchemaType.STRING,
                                       pattern = "^[0-9]{1,6}\\.[0-9]{2}$",
                                       examples = "1000.00")
                               @JsonSerialize(using = ScaledDecimalSerializer.Amount.class)
                               BigDecimal contributed,
                               @Schema(type = SchemaType.STRING,
                                       pattern = "^[0-9]{1,6}\\.[0-9]{2}$",
                                       examples = "1000.00")
                               @JsonSerialize(using = ScaledDecimalSerializer.Amount.class)
                               BigDecimal finalCapital) {

    /**
     * Derives {@code contributed} from the paid-in amounts, as defined by the shared schema:
     * {@code initialAmount + monthlyAmount * durationYears * 12}.
     */
    public SimulationResult(BigDecimal initialAmount, BigDecimal monthlyAmount, int durationYears,
                            BigDecimal finalCapital) {
        this(initialAmount, monthlyAmount, durationYears,
                initialAmount.add(monthlyAmount.multiply(BigDecimal.valueOf(durationYears * 12L))),
                finalCapital);
    }
}
