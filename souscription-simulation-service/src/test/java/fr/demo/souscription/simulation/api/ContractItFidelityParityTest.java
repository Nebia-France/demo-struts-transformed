package fr.demo.souscription.simulation.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity skeleton for 29-simulation-rest-assured-contract-it.
 *
 * SimulationContractIT (plan 29) is the contract test. This skeleton guards the translation itself:
 * the legacy SubscriptionIT read values out of rendered HTML with jsoup, and the migration turns those
 * same facts into JSON assertions. Two things can go wrong silently:
 *
 *  - the assertion is written on a PARSED number, so equalTo(7000.00) passes for 7000.0 and 7.0E3,
 *    both of which break the consumer's ^[0-9]{1,6}\.[0-9]{2}$ pattern;
 *  - a view attribute (step, pageTitle) or the derived gains field leaks onto the wire, re-adding
 *    presentation state this service no longer owns.
 *
 * Copy to souscription-simulation-service/src/test/java/fr/demo/souscription/simulation/api/.
 */
@QuarkusTest
class ContractItFidelityParityTest {

    private static final Pattern MONEY = Pattern.compile("^[0-9]{1,6}\\.[0-9]{2}$");
    private static final Pattern EXPONENT = Pattern.compile("[eE][+-]?[0-9]");

    /** 1000 + 100 x 5 x 12 = 7000.00 -- recomputed, not copied from the plan's snippet. */
    private static final String NOMINAL =
            "{\"initialAmount\":1000.00,\"monthlyAmount\":100.00,\"durationYears\":5,\"annualRate\":0.04}";
    private static final String BELOW_MIN =
            "{\"initialAmount\":99.99,\"monthlyAmount\":100.00,\"durationYears\":5,\"annualRate\":0.04}";

    private static Response post(String body) {
        return given().contentType("application/json").body(body)
                .when().post("/v1/simulations").then().extract().response();
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*([^,}\\s]+)").matcher(body);
        assertTrue(matcher.find(), "field " + name + " not found in: " + body);
        return matcher.group(1).replace("\"", "");
    }

    /** The fact the legacy HTML test asserted, now asserted on JSON -- and on the exact text. */
    @Test
    void nominalContributedTotalIsTheLegacyFigure() {
        Response response = post(NOMINAL);
        assertEquals(200, response.statusCode());
        String body = response.asString();
        assertEquals("7000.00", field(body, "contributed"),
                "contributed must be initial + monthly x years x 12 = 7000.00, with scale 2");
        assertFalse(EXPONENT.matcher(body).find(), "no scientific notation on the wire: " + body);
    }

    @Test
    void everyMonetaryFieldIsScaleTwoAsText() {
        String body = post(NOMINAL).asString();
        for (String name : new String[] {"initialAmount", "monthlyAmount", "contributed", "finalCapital"}) {
            String value = field(body, name);
            assertTrue(MONEY.matcher(value).matches(),
                    name + " must match ^[0-9]{1,6}\\.[0-9]{2}$, was [" + value + "]");
        }
        assertEquals("5", field(body, "durationYears"), "durationYears must stay an integer");
    }

    /** The legacy test asserted a French message inside an HTML element; it now lives in the problem. */
    @Test
    void errorBodiesAreProblemJsonWithWireFieldNames() {
        Response response = post(BELOW_MIN);
        assertEquals(400, response.statusCode());
        assertTrue(response.getContentType().startsWith("application/problem+json"),
                "errors must use application/problem+json, was " + response.getContentType());
        String body = response.asString();
        assertTrue(body.contains("\"initialAmount\""),
                "errors[].field must use the wire name initialAmount, got: " + body);
        assertFalse(body.contains("\"field\":\"initial\""),
                "the legacy key 'initial' belongs to the validator, not to the wire");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        boolean doubleEncoded = false;
        for (int i = 0; i < bytes.length - 1; i++) {
            doubleEncoded |= bytes[i] == (byte) 0xC3 && bytes[i + 1] == (byte) 0x83;
        }
        assertFalse(doubleEncoded, "the French message is double-encoded in the response body");
    }

    /** Exactly five fields: no gains, and no view attribute coming back from the JSP era. */
    @Test
    void responseCarriesExactlyTheContractedFields() {
        String body = post(NOMINAL).asString();
        List<String> expected = List.of("initialAmount", "monthlyAmount", "durationYears",
                "contributed", "finalCapital");
        for (String name : expected) {
            assertTrue(body.contains("\"" + name + "\""), "missing contracted field " + name);
        }
        for (String forbidden : new String[] {"gains", "step", "pageTitle", "sessionId", "csrf"}) {
            assertFalse(body.contains("\"" + forbidden + "\""),
                    "forbidden field [" + forbidden + "] on the wire: " + body);
        }
        long count = Pattern.compile("\"[a-zA-Z]+\"\\s*:").matcher(body).results().count();
        assertEquals(expected.size(), count,
                "the response must carry exactly the five contracted fields, found " + count);
    }

    /** This service produces no HTML: a markup assertion here would be a translation failure. */
    @Test
    void responseIsJsonAndNeverHtml() {
        Response response = post(NOMINAL);
        assertTrue(response.getContentType().startsWith("application/json"),
                "the response must be JSON, was " + response.getContentType());
        String body = response.asString();
        assertFalse(body.contains("<html") || body.contains("<table") || body.contains("<div"),
                "HTML in the response body -- presentation belongs to souscription-web");
    }
}
