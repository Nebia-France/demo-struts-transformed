package fr.demo.souscription.simulation.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import fr.demo.souscription.simulation.model.Simulation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity skeleton for 03-simulation-port-scope-classes.
 *
 * Asserts that the ported SimulationService and SimulationInputValidator reproduce the legacy
 * behaviour recorded in BASELINE.md section 4. Plain JUnit 5 (no @QuarkusTest): plan 03 ports the
 * classes before CDI is introduced in plan 06, so the test must not depend on the container.
 *
 * Copy to souscription-simulation-service/src/test/java/fr/demo/souscription/simulation/service/.
 */
class PortedScopeParityTest {

    private final SimulationService service = new SimulationService();
    private final SimulationInputValidator validator = new SimulationInputValidator();

    /** Legacy reference case: SimulationServiceTest.zeroRateKeepsExactlyTheContributions. */
    @Test
    void zeroRateKeepsExactlyTheContributions() {
        Simulation result = service.calculate(new BigDecimal("1200"), new BigDecimal("100"), 1, BigDecimal.ZERO);
        assertEquals(new BigDecimal("2400.00"), finalCapital(result));
        assertEquals(new BigDecimal("0.00"), gains(result));
        // Scale is part of the wire contract ^[0-9]{1,6}\.[0-9]{2}$ — assert it explicitly.
        assertEquals(2, finalCapital(result).scale());
    }

    /** Legacy reference cases: SimulationServiceTest.agreesWithIndependentClosedForm, tolerance 0.005. */
    @ParameterizedTest
    @CsvSource({"100,0,1,0.02", "1000,100,5,0.04", "100000,2000,30,0.06", "100,2000,1,0.06"})
    void agreesWithIndependentClosedForm(String initial, String monthly, int years, String rate) {
        Simulation result = service.calculate(new BigDecimal(initial), new BigDecimal(monthly),
                years, new BigDecimal(rate));
        double monthlyRate = Double.parseDouble(rate) / 12;
        double multiplier = Math.pow(1 + monthlyRate, years * 12);
        double expected = Double.parseDouble(initial) * multiplier
                + Double.parseDouble(monthly) * (multiplier - 1) / monthlyRate;
        assertEquals(expected, finalCapital(result).doubleValue(), 0.005);
        assertEquals(2, finalCapital(result).scale());
        assertEquals(finalCapital(result).subtract(contributed(result)), gains(result));
    }

    /** Legacy reference cases: SimulationServiceTest.refusesInvalidParameters (7 data sets). */
    @ParameterizedTest
    @CsvSource({"99,0,1", "100001,0,1", "100,-1,1", "100,2001,1", "100,0,0", "100,0,31", "100.001,0,1"})
    void refusesInvalidParameters(String initial, String monthly, int years) {
        assertThrows(IllegalArgumentException.class, () -> service.calculate(
                new BigDecimal(initial), new BigDecimal(monthly), years, new BigDecimal("0.02")));
    }

    /** Guards the source bounds the legacy test did not parameterise: nulls and the rate range. */
    @Test
    void refusesNullsAndOutOfRangeRate() {
        assertThrows(IllegalArgumentException.class,
                () -> service.calculate(null, new BigDecimal("0"), 1, new BigDecimal("0.02")));
        assertThrows(IllegalArgumentException.class,
                () -> service.calculate(new BigDecimal("100"), null, 1, new BigDecimal("0.02")));
        assertThrows(IllegalArgumentException.class,
                () -> service.calculate(new BigDecimal("100"), new BigDecimal("0"), 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.calculate(new BigDecimal("100"), new BigDecimal("0"), 1, new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class,
                () -> service.calculate(new BigDecimal("100"), new BigDecimal("0"), 1, new BigDecimal("1.01")));
    }

    /** Inclusive bounds are accepted: 100 / 100000 / 0 / 2000 / 1 / 30 / 0 / 1. */
    @Test
    void acceptsTheInclusiveBounds() {
        service.calculate(new BigDecimal("100"), new BigDecimal("0"), 1, BigDecimal.ZERO);
        service.calculate(new BigDecimal("100000"), new BigDecimal("2000"), 30, BigDecimal.ONE);
    }

    /**
     * The guard message must be byte-identical UTF-8. A contains("être") assertion also passes when
     * this test source file is itself mis-encoded, so compare the raw bytes.
     */
    @Test
    void guardMessageIsByteIdenticalUtf8() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.calculate(new BigDecimal("99"), BigDecimal.ZERO, 1, BigDecimal.ZERO));
        byte[] expected = new byte[] {
            'P', 'a', 'r', 'a', 'm', (byte) 0xC3, (byte) 0xA8, 't', 'r', 'e', 's', ' ',
            'd', 'e', ' ', 's', 'i', 'm', 'u', 'l', 'a', 't', 'i', 'o', 'n', ' ',
            'i', 'n', 'v', 'a', 'l', 'i', 'd', 'e', 's'
        };
        assertArrayEquals(expected, thrown.getMessage().getBytes(StandardCharsets.UTF_8));
    }

    /** The ported validator keeps the legacy field keys and the legacy French messages. */
    @Test
    void validatorKeepsLegacyKeysAndMessages() {
        Map<String, String> errors = validator.simulationErrors("99", "0", "1");
        assertTrue(errors.containsKey("initial"), "legacy error key 'initial' must be preserved");
        byte[] message = errors.get("initial").getBytes(StandardCharsets.UTF_8);
        // "Le versement initial doit être compris entre 100,00 et 100 000,00 €." + the two-decimals suffix
        assertTrue(contains(message, new byte[] {(byte) 0xC3, (byte) 0xAA, 't', 'r', 'e'}),
                "the 'être' sequence must be encoded as C3 AA 74 72 65");
        assertTrue(contains(message, new byte[] {(byte) 0xE2, (byte) 0x82, (byte) 0xAC}),
                "the euro sign must be encoded as E2 82 AC");
        // Double-encoding signature of 'ê' (C3 83 C2 AA) must be absent.
        assertTrue(!contains(message, new byte[] {(byte) 0xC3, (byte) 0x83, (byte) 0xC2, (byte) 0xAA}),
                "message must not be double-encoded");
    }

    /** Amount normalisation: comma accepted, thousands separator and 3 decimals refused. */
    @ParameterizedTest
    @ValueSource(strings = {"1 000", "1,000.00", "100.001", "100,001", "1e3", "abc", "", " ", "-100", "100.", ".5"})
    void refusesAmbiguousMoney(String literal) {
        Map<String, String> errors = validator.simulationErrors(literal, "0", "1");
        assertTrue(errors.containsKey("initial"), "legacy refused the literal: [" + literal + "]");
    }

    @Test
    void acceptsFrenchAndDotDecimals() {
        assertTrue(validator.simulationErrors("1000,50", "0", "1").isEmpty());
        assertTrue(validator.simulationErrors("1000.50", "0", "1").isEmpty());
    }

    // --- accessor shims -------------------------------------------------------------------------
    // Simulation is a record since plan 08; these helpers delegate to its component accessors.

    private static BigDecimal finalCapital(Simulation s) { return s.finalCapital(); }

    private static BigDecimal contributed(Simulation s) { return s.contributed(); }

    private static BigDecimal gains(Simulation s) { return s.gains(); }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
