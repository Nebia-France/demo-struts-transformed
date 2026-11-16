package fr.demo.souscription.simulation.service;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SimulationInputValidatorTest {
    private final SimulationInputValidator validator = new SimulationInputValidator();

    @Test
    void parsesFrenchAndDotDecimalsAndAcceptsInclusiveBounds() {
        assertEquals(new BigDecimal("100.01"), validator.amount("100,01"));
        assertEquals(new BigDecimal("100.01"), validator.amount("100.01"));
        assertTrue(validator.simulationErrors("100000,00", "2000,00", "30").isEmpty());
        assertTrue(validator.simulationErrors("100", "0", "1").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1e3", "100,001", "1 000,00", "1.000,00", "NaN", "", "100.", "100,", "1000000", " 100"})
    void refusesAmbiguousMoney(String value) { assertNull(validator.amount(value)); }

    @Test
    void enforcesFinancialLimitsAndIntegerYears() {
        assertEquals(3, validator.simulationErrors("99,99", "2000,01", "1.5").size());
    }
}
