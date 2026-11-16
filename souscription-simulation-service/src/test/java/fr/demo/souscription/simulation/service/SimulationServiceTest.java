package fr.demo.souscription.simulation.service;

import fr.demo.souscription.simulation.model.Simulation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class SimulationServiceTest {
    private final SimulationService service = new SimulationService();

    @Test
    void zeroRateKeepsExactlyTheContributions() {
        Simulation result = service.calculate(new BigDecimal("1200"), new BigDecimal("100"), 1, BigDecimal.ZERO);
        assertEquals(new BigDecimal("2400.00"), result.finalCapital());
        assertEquals(new BigDecimal("0.00"), result.gains());
    }

    @ParameterizedTest
    @CsvSource({"100,0,1,0.02", "1000,100,5,0.04", "100000,2000,30,0.06", "100,2000,1,0.06"})
    void agreesWithIndependentClosedForm(String initial, String monthly, int years, String rate) {
        Simulation result = service.calculate(new BigDecimal(initial), new BigDecimal(monthly),
                years, new BigDecimal(rate));
        double monthlyRate = Double.parseDouble(rate) / 12;
        double multiplier = Math.pow(1 + monthlyRate, years * 12);
        double expected = Double.parseDouble(initial) * multiplier
                + Double.parseDouble(monthly) * (multiplier - 1) / monthlyRate;
        assertEquals(expected, result.finalCapital().doubleValue(), 0.005);
        assertEquals(2, result.finalCapital().scale());
        assertEquals(result.finalCapital().subtract(result.contributed()), result.gains());
    }

    @ParameterizedTest
    @CsvSource({"99,0,1", "100001,0,1", "100,-1,1", "100,2001,1", "100,0,0", "100,0,31", "100.001,0,1"})
    void refusesInvalidParameters(String initial, String monthly, int years) {
        assertThrows(IllegalArgumentException.class, () -> service.calculate(
                new BigDecimal(initial), new BigDecimal(monthly), years, new BigDecimal("0.02")));
    }
}
