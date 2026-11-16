package fr.demo.souscription.simulation.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.quarkus.test.junit.QuarkusTest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity skeleton for 28-simulation-port-legacy-unit-tests.
 *
 * This is NOT a replacement for the ported SimulationServiceTest -- that file must stay a verbatim
 * port. This skeleton asserts the two things a verbatim port cannot assert about itself:
 *
 *  1. the French messages are byte-identical UTF-8. A contains("etre")-style assertion also passes
 *     when the TEST SOURCE FILE is itself mis-encoded, so the expected bytes are built from code
 *     points here and the 0xC3 0x83 double-encoding signature is explicitly rejected;
 *  2. the legacy error KEYS (initial, monthly, years) survive in the validator, while the wire names
 *     live only in the mapper (asserted by 16-...-parity).
 *
 * Copy to souscription-simulation-service/src/test/java/fr/demo/souscription/simulation/service/.
 */
@QuarkusTest
class PortedTestFidelityParityTest {

    /** Built from code points so a mis-encoded source file cannot make the test agree with itself. */
    private static final String INITIAL_MESSAGE =
            "Le versement initial doit \u00eatre compris entre 100,00 et 100 000,00 \u20ac.";
    private static final String MONTHLY_MESSAGE =
            "La mensualit\u00e9 doit \u00eatre comprise entre 0,00 et 2 000,00 \u20ac.";
    private static final String YEARS_MESSAGE =
            "La dur\u00e9e doit \u00eatre un nombre entier de 1 \u00e0 30 ans.";
    private static final String DECIMALS_SUFFIX =
            " Utilisez au maximum deux d\u00e9cimales, sans s\u00e9parateur de milliers.";
    private static final String ENGINE_MESSAGE =
            "Param\u00e8tres de simulation invalides";

    @Inject
    SimulationInputValidator validator;

    @Inject
    SimulationService service;

    private static void assertUtf8(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expectedBytes, actualBytes,
                "message bytes differ -- expected [" + expected + "] got [" + actual + "]");
        // 0xC3 0x83 is the signature of a UTF-8 string that was encoded twice (e.g. "Ã©" for "é").
        for (int i = 0; i < actualBytes.length - 1; i++) {
            assertFalse(actualBytes[i] == (byte) 0xC3 && actualBytes[i + 1] == (byte) 0x83,
                    "double-encoding signature 0xC3 0x83 found at index " + i + " in [" + actual + "]");
        }
    }

    /** The legacy keys belong to the validator; the wire names belong to the mapper. */
    @Test
    void validatorKeepsTheLegacyErrorKeysAndMessages() {
        Map<String, String> errors = validator.simulationErrors("99.99", "100.00", "5");
        assertTrue(errors.containsKey("initial"),
                "the validator must keep the legacy key 'initial'; the wire name is the mapper's job");
        assertUtf8(INITIAL_MESSAGE + DECIMALS_SUFFIX, errors.get("initial"));
    }

    @Test
    void monthlyAndYearsMessagesAreByteIdentical() {
        Map<String, String> monthly = validator.simulationErrors("1000.00", "2000.01", "5");
        assertUtf8(MONTHLY_MESSAGE + DECIMALS_SUFFIX, monthly.get("monthly"));

        Map<String, String> years = validator.simulationErrors("1000.00", "100.00", "31");
        assertUtf8(YEARS_MESSAGE, years.get("years"));
    }

    /** The engine message travels into the problem+json body; its bytes are part of the contract. */
    @Test
    void engineRejectionMessageIsByteIdentical() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.calculate(new BigDecimal("99"), BigDecimal.ZERO, 1, BigDecimal.ZERO));
        assertUtf8(ENGINE_MESSAGE, thrown.getMessage());
    }

    /** The five legacy normalisation verdicts, called directly (a thousands separator never reaches JSON). */
    @ParameterizedTest
    @CsvSource(value = {
            "1000,50|1000.50",
            "1000.50|1000.50",
            "1 000,50|NULL",
            "abc|NULL",
            "1000,555|NULL"
    }, delimiter = '|')
    void normalisationVerdictsAreUnchanged(String input, String expected) {
        BigDecimal actual = validator.amount(input);
        if ("NULL".equals(expected)) {
            assertNull(actual, "[" + input + "] must be refused as it was by the legacy regex");
        } else {
            assertEquals(0, new BigDecimal(expected).compareTo(actual),
                    "[" + input + "] must normalise to " + expected);
        }
    }

    /** The reference case of the whole migration (BASELINE.md section 4). */
    @Test
    void referenceCaseStillHoldsThroughThePortedService() {
        var result = service.calculate(new BigDecimal("1200"), new BigDecimal("100"), 1, BigDecimal.ZERO);
        assertEquals(new BigDecimal("2400.00"), result.finalCapital());
        assertEquals(new BigDecimal("0.00"), result.gains());
        assertEquals(2, result.finalCapital().scale(), "scale must be exactly 2");
    }

    /** The subscriber half must not have been ported into this module. */
    @Test
    void noSubscriberValidationLeakedIntoThisModule() {
        for (String method : new String[] {"subscriberErrors", "validateEmail", "validateBirthDate"}) {
            boolean present = java.util.Arrays.stream(validator.getClass().getMethods())
                    .anyMatch(m -> m.getName().equals(method));
            assertFalse(present, "subscriber validation [" + method + "] belongs to souscription-service");
        }
    }
}
