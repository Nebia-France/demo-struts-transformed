package fr.demo.souscription.simulation.api;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import fr.demo.souscription.simulation.service.SimulationInputValidator;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation and fidelity contract matrix (W3-T05).
 *
 * <p>One row per bound of the legacy {@code InputValidator} / {@code SimulationService} guard,
 * taken verbatim from the {@code Examples} tables of {@code features/simulation/simulation.feature}.
 * Every row asserts the three dimensions of the contract at once:
 * <ul>
 *   <li>the HTTP verdict at the bound,</li>
 *   <li>on 200, scale 2 on the four monetary fields, asserted on the RAW body text so a collapsed
 *       scale (2400 instead of 2400.00) or a normalised exponent (2.4E+3) cannot pass,</li>
 *   <li>on 400, the {@code application/problem+json} media type and the faulty property name in
 *       {@code errors[0].field}.</li>
 * </ul>
 */
@QuarkusTest
@Tag("contract")
class SimulationValidationIT {

    /** The contract pattern of the monetary fields: exactly two decimals, dot separator. */
    private static final Pattern MONEY = Pattern.compile("^[0-9]{1,6}\\.[0-9]{2}$");

    private static final String[] MONETARY_FIELDS =
            {"initialAmount", "monthlyAmount", "contributed", "finalCapital"};

    @Inject
    SimulationInputValidator validator;

    // ---------------------------------------------------------------- bounds

    /** initialAmount in [100.00, 100000.00] inclusive. */
    @ParameterizedTest
    @CsvSource({"99.99,400", "100.00,200", "100000.00,200", "100000.01,400"})
    void initialAmountBounds(String value, int expected) {
        assertOutcome(value, "0.00", "1", "0", expected, "initialAmount");
    }

    /** monthlyAmount in [0.00, 2000.00] inclusive. */
    @ParameterizedTest
    @CsvSource({"0.00,200", "2000.00,200", "2000.01,400"})
    void monthlyAmountBounds(String value, int expected) {
        assertOutcome("100.00", value, "1", "0", expected, "monthlyAmount");
    }

    /** durationYears a whole number in [1, 30]. */
    @ParameterizedTest
    @CsvSource({"0,400", "1,200", "30,200", "31,400"})
    void durationYearsBounds(String value, int expected) {
        assertOutcome("100.00", "0.00", value, "0", expected, "durationYears");
    }

    /** annualRate in [0, 1] inclusive -- the legacy guard refused rate &gt; 1. */
    @ParameterizedTest
    @CsvSource({"0,200", "1,200", "1.01,400"})
    void annualRateBounds(String value, int expected) {
        assertOutcome("100.00", "0.00", "1", value, expected, "annualRate");
    }

    /**
     * A non-integer duration is refused (Gherkin row {@code 5,5}). The fractional literal is not a
     * whole number, so it is rejected at deserialisation rather than silently truncated to 5, and
     * the rejection still carries the durationYears property name.
     */
    @Test
    void nonIntegerDurationIsRefused() {
        Response response = post("100.00", "0.00", "5.5", "0");
        assertEquals(400, response.statusCode(), response.asString());
        assertProblemJson(response);
        assertEquals("durationYears", response.jsonPath().getString("errors[0].field"),
                response.asString());
    }

    /** A missing duration is refused on the durationYears property (Gherkin empty row). */
    @Test
    void missingDurationIsRefused() {
        Response response = given().contentType("application/json")
                .body("{\"initialAmount\":100.00,\"monthlyAmount\":0.00,\"annualRate\":0}")
                .when().post("/v1/simulations")
                .then().extract().response();
        assertEquals(400, response.statusCode(), response.asString());
        assertProblemJson(response);
        assertEquals("durationYears", response.jsonPath().getString("errors[0].field"),
                response.asString());
    }

    /**
     * The seven data sets refused by the legacy {@code SimulationServiceTest.refusesInvalidParameters}
     * must still be refused over the wire, each on its own wire property name. {@code 100.001} is
     * refused because of the two-decimal scale, not because of a bound: a matrix limited to the
     * bounds would miss it, as would one limited to the positive side of {@code monthlyAmount}.
     */
    @ParameterizedTest
    @CsvSource({
            "99,0,1,initialAmount",
            "100001,0,1,initialAmount",
            "100,-1,1,monthlyAmount",
            "100,2001,1,monthlyAmount",
            "100,0,0,durationYears",
            "100,0,31,durationYears",
            "100.001,0,1,initialAmount"
    })
    void legacyRefusedDataSetsStayRefused(String initial, String monthly, String years, String field) {
        Response response = post(initial, monthly, years, "0.02");
        assertEquals(400, response.statusCode(), response.asString());
        assertProblemJson(response);
        assertEquals(field, response.jsonPath().getString("errors[0].field"), response.asString());
    }

    // ------------------------------------------- string normalisation (unit)

    /**
     * {@code SimulationInputValidator.amount(String)} keeps the legacy regex behaviour exactly:
     * the comma is accepted as a decimal separator, a thousands separator and a third decimal are
     * refused. Exercised directly, not through HTTP: the wire contract carries already-normalised
     * amounts, the string form only exists at the former form-binding boundary.
     */
    @ParameterizedTest
    @CsvSource(value = {
            "1000,50|1000.50",
            "1000.50|1000.50",
            "1 000,50|",
            "abc|",
            "1000,555|"
    }, delimiter = '|')
    void amountNormalisationKeepsLegacyOutcomes(String raw, String expected) {
        BigDecimal actual = validator.amount(raw);
        if (expected == null) {
            assertNull(actual, "[" + raw + "] must be refused by the legacy regex");
        } else {
            assertEquals(new BigDecimal(expected), actual, "[" + raw + "] must normalise to " + expected);
            assertEquals(2, actual.scale(), "the normalised amount keeps the two decimals of the input");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Response post(String initial, String monthly, String years, String rate) {
        return given().contentType("application/json")
                .body(String.format(
                        "{\"initialAmount\":%s,\"monthlyAmount\":%s,\"durationYears\":%s,\"annualRate\":%s}",
                        initial, monthly, years, rate))
                .when().post("/v1/simulations")
                .then().extract().response();
    }

    private static void assertOutcome(String initial, String monthly, String years, String rate,
            int expected, String field) {
        Response response = post(initial, monthly, years, rate);
        assertEquals(expected, response.statusCode(), response.asString());
        if (expected == 200) {
            assertScaleTwoOnEveryMonetaryField(response.asString());
        } else {
            assertProblemJson(response);
            assertEquals(field, response.jsonPath().getString("errors[0].field"), response.asString());
        }
    }

    private static void assertProblemJson(Response response) {
        assertTrue(response.contentType().startsWith("application/problem+json"),
                "an error response must use the RFC 7807 media type, was [" + response.contentType()
                        + "], body: " + response.asString());
    }

    private static void assertScaleTwoOnEveryMonetaryField(String body) {
        for (String field : MONETARY_FIELDS) {
            String value = extract(body, field);
            assertTrue(MONEY.matcher(value).matches(),
                    field + " must match ^[0-9]{1,6}\\.[0-9]{2}$ (scale 2, no exponent), was ["
                            + value + "]");
        }
    }

    private static String extract(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*([^,}\\s]+)").matcher(body);
        assertTrue(matcher.find(), "field " + field + " not found in body: " + body);
        return matcher.group(1).replace("\"", "");
    }
}
