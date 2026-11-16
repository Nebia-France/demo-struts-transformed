package fr.demo.souscription.simulation.api;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import fr.demo.souscription.simulation.model.Simulation;
import fr.demo.souscription.simulation.service.SimulationService;
import io.quarkus.test.junit.QuarkusTest;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity skeleton for 12-simulation-decimal-arithmetic-contract.
 *
 * Every assertion here is on the RAW RESPONSE BODY TEXT. Parsing finalCapital to a double or a
 * BigDecimal would hide a collapsed scale (2400 instead of 2400.00) and a normalised exponent
 * (2.4E+3), both of which violate the contract pattern ^[0-9]{1,6}\.[0-9]{2}$. The legacy monolith
 * gave no wire guarantee (it formatted at JSP render time), so this is a strengthening to verify,
 * not a before/after value change -- the VALUES are still compared against the ported service.
 *
 * Copy to souscription-simulation-service/src/test/java/fr/demo/souscription/simulation/api/.
 */
@QuarkusTest
class DecimalWireParityTest {

    private static final Pattern EXPONENT = Pattern.compile("[eE][+-]?[0-9]");
    /** Input amounts are bounded by the input constraints: at most six integer digits. */
    private static final Pattern INPUT_MONEY = Pattern.compile("^[0-9]{1,6}\\.[0-9]{2}$");
    /**
     * Derived amounts are DECIMAL(14,2) and legitimately exceed six integer digits: the legacy
     * reference case 100000/2000/30/0.06 yields contributed 820000.00 and finalCapital 2611287.61.
     *
     * <p>The frozen global/contracts/shared-schemas/SimulationResult.json still declares
     * {@code ^[0-9]{1,6}\.[0-9]{2}$} for these two fields, which cannot express 2611287.61. That is
     * a defect of the frozen document, not of the implementation: the frozen file is left untouched
     * (the openapi-contract-diff gate forbids editing it) and the wider range is carried on the
     * implementation side only, in SimulationResponse. It needs a contract-owner amendment.
     */
    private static final Pattern DERIVED_MONEY = Pattern.compile("^[0-9]{1,12}\\.[0-9]{2}$");

    @Inject
    SimulationService service;

    private static String rawBody(String initial, String monthly, int years, String rate) {
        String body = String.format(
                "{\"initialAmount\":%s,\"monthlyAmount\":%s,\"durationYears\":%d,\"annualRate\":%s}",
                initial, monthly, years, rate);
        return given().contentType("application/json").body(body)
                .when().post("/v1/simulations")
                .then().statusCode(200)
                .extract().response().asString();
    }

    /**
     * The exact decimal string, scale included, must appear in the body text.
     *
     * <p>The frozen contract global/contracts/shared-schemas/SimulationResult.json types the four
     * monetary fields as {@code "type": "string"} with pattern {@code ^[0-9]{1,6}\.[0-9]{2}$}, so
     * the literal on the wire is quoted. The scale assertion is unchanged; only the quoting of the
     * expected literal follows the frozen document, which the plan-10 contract diff forbids
     * changing.
     */
    @Test
    void referenceCaseKeepsItsScaleOnTheWire() {
        String body = rawBody("1200", "100", 1, "0");
        assertTrue(body.contains("\"finalCapital\":\"2400.00\""),
                "expected the literal 2400.00 in the body, got: " + body);
        assertTrue(body.contains("\"contributed\":\"2400.00\""),
                "expected the literal 2400.00 for contributed, got: " + body);
        assertFalse(body.contains("\"finalCapital\":\"2400.0\""), "scale must not collapse to one decimal");
        assertFalse(body.contains("\"finalCapital\":\"2400\""), "scale must not collapse to zero decimals");
    }

    /** No scientific notation anywhere, and every monetary field matches the contract pattern. */
    @ParameterizedTest
    @CsvSource({"100,0,1,0.02", "1000,100,5,0.04", "100000,2000,30,0.06", "100,2000,1,0.06"})
    void noExponentAndEveryMonetaryFieldMatchesThePattern(String initial, String monthly, int years, String rate) {
        String body = rawBody(initial, monthly, years, rate);
        assertFalse(EXPONENT.matcher(body).find(), "no scientific notation allowed on the wire: " + body);
        for (String field : new String[] {"initialAmount", "monthlyAmount"}) {
            String value = extract(body, field);
            assertTrue(INPUT_MONEY.matcher(value).matches(),
                    field + " must match ^[0-9]{1,6}\\.[0-9]{2}$, was [" + value + "]");
        }
        for (String field : new String[] {"contributed", "finalCapital"}) {
            String value = extract(body, field);
            assertTrue(DERIVED_MONEY.matcher(value).matches(),
                    field + " must match ^[0-9]{1,12}\\.[0-9]{2}$, was [" + value + "]");
        }
    }

    /** The HTTP result must equal the in-process (ported) result exactly, value and scale. */
    @ParameterizedTest
    @CsvSource({"100,0,1,0.02", "1000,100,5,0.04", "100000,2000,30,0.06", "100,2000,1,0.06"})
    void legacyReferenceCases(String initial, String monthly, int years, String rate) {
        Simulation inProcess = service.calculate(new BigDecimal(initial), new BigDecimal(monthly),
                years, new BigDecimal(rate));
        String body = rawBody(initial, monthly, years, rate);
        assertEquals(inProcess.finalCapital().toPlainString(), extract(body, "finalCapital"),
                "the serialised value must be the plain string of the computed BigDecimal");
        assertEquals(inProcess.contributed().toPlainString(), extract(body, "contributed"));
    }

    /**
     * Rounding happens at the .005 boundary, in the HALF direction, through the service.
     *
     * <p>The two cases below were found by replaying the DECIMAL128 loop over the whole input
     * domain and keeping the results closest to a tie on either side:
     * <ul>
     *   <li>497.80 / 0 / 17 years / 2% gives an unrounded capital of 699.18500000001294..., one
     *       nanocent ABOVE the tie: HALF_UP (and any HALF_* mode) gives 699.19, while FLOOR,
     *       DOWN or a double-based computation give 699.18.</li>
     *   <li>241.64 / 0 / 11 years / 6% gives 466.75499999554375..., just BELOW the tie: the cent
     *       must stay 466.75, which rules out CEILING and UP.</li>
     * </ul>
     *
     * <p>An input whose unrounded capital ends in exactly .005 does not exist: the capital is
     * {@code initial * f^n + monthly * sum(f^i)} with {@code f = 1 + rate/12}, and such a value can
     * only terminate at the third decimal if the numerator is divisible by a power of ten far
     * beyond the six-digit input domain. HALF_UP therefore cannot be told apart from HALF_EVEN or
     * HALF_DOWN through the API; the mode itself is asserted on the source of truth
     * ({@code capital.setScale(2, RoundingMode.HALF_UP)}) by PortedScopeParityTest's body diff.
     */
    @ParameterizedTest
    @CsvSource({"497.80,0,17,0.02,699.19", "241.64,0,11,0.06,466.75"})
    void roundingIsHalfAtTheHundredthBoundary(String initial, String monthly, int years,
            String rate, String expected) {
        Simulation inProcess = service.calculate(new BigDecimal(initial), new BigDecimal(monthly),
                years, new BigDecimal(rate));
        assertEquals(expected, inProcess.finalCapital().toPlainString(),
                "the rounded cent at the .005 boundary must follow HALF_UP");
        String body = rawBody(initial, monthly, years, rate);
        assertTrue(body.contains("\"finalCapital\":\"" + expected + "\""),
                "expected the literal " + expected + " on the wire, got: " + body);
    }

    /** The scale is always exactly 2, in process and on the wire, over the whole duration range. */
    @Test
    void scaleIsAlwaysTwoAcrossEveryDuration() {
        for (int years = 1; years <= 30; years++) {
            Simulation result = service.calculate(new BigDecimal("100"), new BigDecimal("0"),
                    years, new BigDecimal("0.06"));
            assertEquals(2, result.finalCapital().scale(), "scale must always be exactly 2");
            String onTheWire = extract(rawBody("100", "0", years, "0.06"), "finalCapital");
            assertEquals(result.finalCapital().toPlainString(), onTheWire);
        }
    }

    /** durationYears must stay an integer on the wire, never 1.0. */
    @Test
    void durationIsAnIntegerOnTheWire() {
        String body = rawBody("1200", "100", 1, "0");
        assertTrue(body.contains("\"durationYears\":1"), "durationYears must serialise as 1, got: " + body);
        assertFalse(body.contains("\"durationYears\":1.0"), "durationYears must not become a decimal");
    }

    private static String extract(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*([^,}\\s]+)").matcher(body);
        assertTrue(m.find(), "field " + field + " not found in body: " + body);
        return m.group(1).replace("\"", "");
    }
}
