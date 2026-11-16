package fr.demo.souscription.simulation.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Les montants et le taux voyagent en chaîne (global/contracts/shared-schemas/
 * SimulationComputeRequest.json) pour préserver les échelles DECIMAL(14,2) et DECIMAL(8,6).
 */
@Schema(name = "SimulationComputeRequest",
        description = "Internal request to souscription-simulation-service. Carries the authoritative"
                + " annualRate obtained from souscription-catalogue-service; the simulation service"
                + " never reads the catalogue itself.")
public record SimulationRequest(
        @NotNull(message = "Le versement initial doit être compris entre 100,00 et 100 000,00 €. Utilisez au maximum deux décimales, sans séparateur de milliers.")
        @DecimalMin(value = "100",
                message = "Le versement initial doit être compris entre 100,00 et 100 000,00 €. Utilisez au maximum deux décimales, sans séparateur de milliers.")
        @DecimalMax(value = "100000",
                message = "Le versement initial doit être compris entre 100,00 et 100 000,00 €. Utilisez au maximum deux décimales, sans séparateur de milliers.")
        @Digits(integer = 6, fraction = 2,
                message = "Le versement initial doit être compris entre 100,00 et 100 000,00 €. Utilisez au maximum deux décimales, sans séparateur de milliers.")
        @Schema(type = SchemaType.STRING,
                pattern = "^[0-9]{1,6}\\.[0-9]{2}$",
                example = "1000.00",
                required = true,
                description = "Decimal amount in EUR, DECIMAL(14,2), serialised as a JSON string to"
                        + " preserve scale (two decimals, dot separator, no thousands separator)."
                        + " Between 100.00 and 100000.00 inclusive.")
        BigDecimal initialAmount,

        @NotNull(message = "La mensualité doit être comprise entre 0,00 et 2 000,00 €. Utilisez au maximum deux décimales, sans séparateur de milliers.")
        @DecimalMin(value = "0",
                message = "La mensualité doit être comprise entre 0,00 et 2 000,00 €. Utilisez au maximum deux décimales, sans séparateur de milliers.")
        @DecimalMax(value = "2000",
                message = "La mensualité doit être comprise entre 0,00 et 2 000,00 €. Utilisez au maximum deux décimales, sans séparateur de milliers.")
        @Digits(integer = 6, fraction = 2,
                message = "La mensualité doit être comprise entre 0,00 et 2 000,00 €. Utilisez au maximum deux décimales, sans séparateur de milliers.")
        @Schema(type = SchemaType.STRING,
                pattern = "^[0-9]{1,6}\\.[0-9]{2}$",
                example = "100.00",
                required = true,
                description = "Decimal amount in EUR, DECIMAL(14,2), serialised as a JSON string to"
                        + " preserve scale (two decimals, dot separator, no thousands separator)."
                        + " Between 0.00 and 2000.00 inclusive.")
        BigDecimal monthlyAmount,

        @Min(value = 1, message = "La durée doit être un nombre entier de 1 à 30 ans.")
        @Max(value = 30, message = "La durée doit être un nombre entier de 1 à 30 ans.")
        @Schema(type = SchemaType.INTEGER,
                minimum = "1",
                maximum = "30",
                example = "10",
                required = true,
                description = "Duration of the savings plan in whole years.")
        int durationYears,

        @NotNull(message = "Paramètres de simulation invalides")
        @DecimalMin(value = "0", message = "Paramètres de simulation invalides")
        @DecimalMax(value = "1", message = "Paramètres de simulation invalides")
        @Schema(type = SchemaType.STRING,
                pattern = "^[0-9]\\.[0-9]{6}$",
                example = "0.020000",
                required = true,
                description = "Annual rate as a decimal fraction, DECIMAL(8,6), serialised as a string"
                        + " with exactly six decimals to preserve scale (0.020000 = 2%).")
        BigDecimal annualRate) {
}
