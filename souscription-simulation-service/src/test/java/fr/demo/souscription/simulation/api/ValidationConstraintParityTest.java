package fr.demo.souscription.simulation.api;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.quarkus.test.junit.QuarkusTest;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity skeleton for 13-simulation-jakarta-validation-constraints.
 *
 * Two dimensions: the VERDICT at every legacy bound, and the MESSAGE bytes. The message assertions
 * compare raw UTF-8 byte sequences because a contains("etre") style assertion also passes when this
 * test source file is itself mis-encoded, and because mojibake decodes back to a String that looks
 * plausible in a report.
 *
 * Copy to souscription-simulation-service/src/test/java/fr/demo/souscription/simulation/api/.
 */
@QuarkusTest
class ValidationConstraintParityTest {

    private static int status(String initial, String monthly, String years, String rate) {
        return given().contentType("application/json")
                .body(String.format(
                        "{\"initialAmount\":%s,\"monthlyAmount\":%s,\"durationYears\":%s,\"annualRate\":%s}",
                        initial, monthly, years, rate))
                .when().post("/v1/simulations")
                .then().extract().statusCode();
    }

    private static byte[] body(String initial, String monthly, String years, String rate) {
        return given().contentType("application/json")
                .body(String.format(
                        "{\"initialAmount\":%s,\"monthlyAmount\":%s,\"durationYears\":%s,\"annualRate\":%s}",
                        initial, monthly, years, rate))
                .when().post("/v1/simulations")
                .then().extract().response().asByteArray();
    }

    /** initial in [100, 100000] inclusive -- the legacy bounds of InputValidator / the guard. */
    @ParameterizedTest
    @CsvSource({"99.99,400", "100.00,200", "100000.00,200", "100000.01,400"})
    void initialAmountBounds(String value, int expected) {
        assertEquals(expected, status(value, "0", "1", "0"));
    }

    /** monthly in [0, 2000] inclusive. */
    @ParameterizedTest
    @CsvSource({"-0.01,400", "0.00,200", "2000.00,200", "2000.01,400"})
    void monthlyAmountBounds(String value, int expected) {
        assertEquals(expected, status("100", value, "1", "0"));
    }

    /** years an integer in [1, 30]. */
    @ParameterizedTest
    @CsvSource({"0,400", "1,200", "30,200", "31,400"})
    void durationYearsBounds(String value, int expected) {
        assertEquals(expected, status("100", "0", value, "0"));
    }

    /** rate in [0, 1] inclusive -- the legacy guard refused rate < 0 and rate > 1. */
    @ParameterizedTest
    @CsvSource({"-0.01,400", "0,200", "1,200", "1.01,400"})
    void annualRateBounds(String value, int expected) {
        assertEquals(expected, status("100", "0", "1", value));
    }

    /** The legacy guard refused scale() > 2 (SimulationServiceTest data set 100.001/0/1). */
    @Test
    void threeDecimalsAreRefused() {
        assertEquals(400, status("100.001", "0", "1", "0"));
        assertEquals(400, status("100", "0.001", "1", "0"));
    }

    @Test
    void missingFieldsAreRefused() {
        int missing = given().contentType("application/json").body("{}")
                .when().post("/v1/simulations").then().extract().statusCode();
        assertEquals(400, missing, "all four fields are required by the frozen request schema");
    }

    /** The three French messages must appear in the response as byte-identical UTF-8. */
    @Test
    void messagesAreByteIdenticalUtf8() {
        byte[] initialError = body("99", "0", "1", "0");
        assertContains(initialError, "Le versement initial doit \u00eatre compris entre 100,00 et 100 000,00 \u20ac.");

        byte[] monthlyError = body("100", "2001", "1", "0");
        assertContains(monthlyError, "La mensualit\u00e9 doit \u00eatre comprise entre 0,00 et 2 000,00 \u20ac.");

        byte[] yearsError = body("100", "0", "31", "0");
        assertContains(yearsError, "La dur\u00e9e doit \u00eatre un nombre entier de 1 \u00e0 30 ans.");
    }

    /** The two-decimals suffix is appended to the amount messages (legacy InputValidator line 71). */
    @Test
    void twoDecimalsSuffixIsPreserved() {
        byte[] scaleError = body("100.001", "0", "1", "0");
        assertContains(scaleError,
                " Utilisez au maximum deux d\u00e9cimales, sans s\u00e9parateur de milliers.");
    }

    /** No double-encoded accent anywhere in an error body. */
    @Test
    void noMojibakeInErrorBodies() {
        byte[] error = body("99", "0", "1", "0");
        // 'e' with circumflex double-encoded is C3 83 C2 AA; 'e' with acute is C3 83 C2 A9.
        assertFalse(contains(error, new byte[] {(byte) 0xC3, (byte) 0x83, (byte) 0xC2, (byte) 0xAA}),
                "double-encoded circumflex found: the message source is not read as UTF-8");
        assertFalse(contains(error, new byte[] {(byte) 0xC3, (byte) 0x83, (byte) 0xC2, (byte) 0xA9}),
                "double-encoded acute found: the message source is not read as UTF-8");
    }

    /** Hibernate Validator defaults must not replace the legacy messages. */
    @Test
    void noFrameworkDefaultMessageLeaks() {
        String error = new String(body("99", "0", "1", "0"), StandardCharsets.UTF_8);
        assertFalse(error.contains("must be greater than or equal to"),
                "the Hibernate Validator default message leaked: the annotation has no message attribute");
        assertFalse(error.contains("numeric value out of bounds"),
                "the @Digits default message leaked");
    }

    private static void assertContains(byte[] haystack, String expectedText) {
        assertTrue(contains(haystack, expectedText.getBytes(StandardCharsets.UTF_8)),
                "expected the exact UTF-8 bytes of [" + expectedText + "] in the response body, got: "
                        + new String(haystack, StandardCharsets.UTF_8));
    }

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
