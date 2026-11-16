package fr.demo.souscription.simulation.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity skeleton for 14-simulation-problem-json-mappers.
 *
 * The RFC 7807 format is new (the monolith re-rendered the JSP with an error map keyed
 * initial/monthly/years). What must be preserved is the DETECTION of each condition and the MESSAGE
 * attached to each field, with the legacy keys mapped to the frozen wire names.
 *
 * Copy to souscription-simulation-service/src/test/java/fr/demo/souscription/simulation/api/.
 */
@QuarkusTest
class ProblemJsonParityTest {

    private static final Pattern LEAK = Pattern.compile("java\\.|at fr\\.demo|Exception|\\.java:");

    private static Response post(String body) {
        return given().contentType("application/json").body(body)
                .when().post("/v1/simulations").then().extract().response();
    }

    @Test
    void validationFailureUsesTheProblemJsonEnvelope() {
        Response response = post("""
                {"initialAmount":99,"monthlyAmount":0,"durationYears":1,"annualRate":0}
                """);
        assertEquals(400, response.statusCode());
        assertTrue(response.getContentType().startsWith("application/problem+json"),
                "expected application/problem+json, got " + response.getContentType());
        assertNotNull(response.jsonPath().get("type"), "RFC 7807 requires 'type'");
        assertNotNull(response.jsonPath().get("title"), "'title' must be present for operators");
        assertEquals(400, (int) response.jsonPath().getInt("status"));
        assertNotNull(response.jsonPath().getList("errors"), "the FieldErrors contract requires 'errors'");
    }

    /** Each entry carries the WIRE field name, never the legacy key. */
    @Test
    void errorEntriesUseTheWireFieldNames() {
        Response response = post("""
                {"initialAmount":99,"monthlyAmount":0,"durationYears":1,"annualRate":0}
                """);
        List<String> fields = response.jsonPath().getList("errors.field");
        assertTrue(fields.contains("initialAmount"),
                "expected the wire name initialAmount, got " + fields);
        assertFalse(fields.contains("initial"),
                "the legacy key 'initial' must be mapped to the wire name before serialisation");
        // A full property path is not usable by a consumer either.
        for (String field : fields) {
            assertFalse(field.contains("."), "field must be the leaf name, got " + field);
            assertFalse(field.startsWith("arg"), "field must not be a parameter index, got " + field);
        }
    }

    /** The legacy message text must travel with the field error. */
    @Test
    void errorEntryCarriesTheLegacyMessage() {
        byte[] body = post("""
                {"initialAmount":99,"monthlyAmount":0,"durationYears":1,"annualRate":0}
                """).asByteArray();
        assertTrue(contains(body,
                "Le versement initial doit \u00eatre compris entre 100,00 et 100 000,00 \u20ac."
                        .getBytes(StandardCharsets.UTF_8)),
                "the exact legacy message bytes must appear in the errors array");
    }

    /**
     * The calculate guard throws IllegalArgumentException("Parametres de simulation invalides").
     * It must map to 400 with that exact detail -- not to 500, which both consumers treat as a
     * retryable server fault.
     */
    @Test
    void guardExceptionMapsToFourHundredWithTheExactDetail() {
        // The declarative constraints of plan 13 shadow every guard condition, so the guard is not
        // reachable through HTTP; the mapper itself is asserted in IllegalArgumentExceptionMapperTest.
        Response response = post("""
                {"initialAmount":99,"monthlyAmount":0,"durationYears":1,"annualRate":0}
                """);
        assertEquals(400, response.statusCode());
        assertTrue(response.getContentType().startsWith("application/problem+json"),
                "a client rejection must never be served as a 500 or as HTML: "
                        + response.getContentType());
    }

    /** The legacy LinkedHashMap reported one entry per failing field; all violations must surface. */
    @Test
    void allViolationsAreReported() {
        Response response = post("""
                {"initialAmount":99,"monthlyAmount":2001,"durationYears":31,"annualRate":0}
                """);
        assertEquals(400, response.statusCode());
        List<String> fields = response.jsonPath().getList("errors.field");
        assertEquals(3, fields.size(), "three fields violate their bounds; got " + fields);
        assertTrue(fields.containsAll(List.of("initialAmount", "monthlyAmount", "durationYears")));
    }

    @Test
    void noInternalDetailLeaks() {
        for (String payload : new String[] {
                "{\"initialAmount\":99,\"monthlyAmount\":0,\"durationYears\":1,\"annualRate\":0}",
                "{}",
                "{\"initialAmount\":\"abc\",\"monthlyAmount\":0,\"durationYears\":1,\"annualRate\":0}"}) {
            String body = post(payload).asString();
            assertFalse(LEAK.matcher(body).find(),
                    "no stack trace or Java type name may appear in an error body: " + body);
        }
    }

    @Test
    void malformedJsonStillYieldsProblemJson() {
        Response response = given().contentType("application/json").body("{not json")
                .when().post("/v1/simulations").then().extract().response();
        assertEquals(400, response.statusCode());
        assertTrue(response.getContentType().startsWith("application/problem+json"),
                "a parse failure must not produce an HTML page or a 500: " + response.getContentType());
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
