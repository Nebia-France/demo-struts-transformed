package fr.demo.souscription.simulation.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import fr.demo.souscription.simulation.model.Simulation;
import java.math.BigDecimal;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Les montants sont sérialisés en chaîne (SimulationResult.json) pour préserver l'échelle 2.
 */
@Schema(name = "SimulationResult",
        description = "Compound-interest projection. Computed monthly with MathContext.DECIMAL128 and"
                + " rounded HALF_UP to two decimals, identical to the legacy SimulationService.")
public record SimulationResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(type = SchemaType.STRING,
                pattern = "^[0-9]{1,6}\\.[0-9]{2}$",
                format = "decimal",
                multipleOf = 0.01,
                example = "1000.00",
                required = true,
                description = "Decimal amount in EUR, DECIMAL(14,2), serialised as a JSON string to"
                        + " preserve scale (two decimals, dot separator, no thousands separator)."
                        + " Between 100.00 and 100000.00 inclusive.")
        BigDecimal initialAmount,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(type = SchemaType.STRING,
                pattern = "^[0-9]{1,6}\\.[0-9]{2}$",
                format = "decimal",
                multipleOf = 0.01,
                example = "100.00",
                required = true,
                description = "Decimal amount in EUR, DECIMAL(14,2), serialised as a JSON string to"
                        + " preserve scale (two decimals, dot separator, no thousands separator)."
                        + " Between 0.00 and 2000.00 inclusive.")
        BigDecimal monthlyAmount,

        @Schema(type = SchemaType.INTEGER,
                minimum = "1",
                maximum = "30",
                example = "10",
                required = true,
                description = "Duration of the savings plan in whole years.")
        int durationYears,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(type = SchemaType.STRING,
                pattern = "^[0-9]{1,12}\\.[0-9]{2}$",
                format = "decimal",
                multipleOf = 0.01,
                example = "13000.00",
                required = true,
                description = "Total amount paid in: initialAmount + monthlyAmount * durationYears * 12."
                        + " Scale 2. Derived value, DECIMAL(14,2): it exceeds the six integer digits"
                        + " of the input amounts.")
        BigDecimal contributed,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(type = SchemaType.STRING,
                pattern = "^[0-9]{1,12}\\.[0-9]{2}$",
                format = "decimal",
                multipleOf = 0.01,
                example = "14512.33",
                required = true,
                description = "Projected capital at term, rounded HALF_UP to scale 2. Authoritative"
                        + " server-side value; a client-supplied value is never trusted. Derived"
                        + " value, DECIMAL(14,2): compounding takes it past six integer digits.")
        BigDecimal finalCapital) {

    public static SimulationResponse from(Simulation simulation) {
        return new SimulationResponse(
                simulation.initial(),
                simulation.monthly(),
                simulation.years(),
                simulation.contributed(),
                simulation.finalCapital());
    }
}
