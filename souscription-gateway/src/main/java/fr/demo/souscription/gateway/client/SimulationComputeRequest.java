package fr.demo.souscription.gateway.client;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import fr.demo.souscription.gateway.api.dto.ScaledDecimalSerializer;
import fr.demo.souscription.gateway.api.dto.SimulationInput;
import java.math.BigDecimal;

/**
 * Internal request body of {@code POST /v1/simulations}, as declared by
 * {@code global/contracts/shared-schemas/SimulationComputeRequest.json}.
 *
 * <p>The amounts and the rate travel as strings so the {@code DECIMAL(14,2)} and
 * {@code DECIMAL(8,6)} scales are preserved on the wire.
 */
public record SimulationComputeRequest(
        @JsonSerialize(using = ScaledDecimalSerializer.Amount.class) BigDecimal initialAmount,
        @JsonSerialize(using = ScaledDecimalSerializer.Amount.class) BigDecimal monthlyAmount,
        int durationYears,
        @JsonSerialize(using = ScaledDecimalSerializer.Rate.class) BigDecimal annualRate) {

    public static SimulationComputeRequest of(SimulationInput input, BigDecimal annualRate) {
        return new SimulationComputeRequest(input.initialAmount(), input.monthlyAmount(),
                input.durationYears(), annualRate);
    }
}
