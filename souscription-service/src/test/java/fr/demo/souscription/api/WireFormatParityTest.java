package fr.demo.souscription.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Parity skeleton for 18-souscription-service-json-types-errors.
 *
 * Baseline reference: BASELINE.md sections 4.5 and 5.
 * The source gave NO wire guarantee: amounts travelled as French strings and dates in the local
 * day/month/year form. The frozen schemas declare scale-preserving JSON STRINGS, so this is a
 * strengthening to verify -- and it can only be verified on the RAW BODY TEXT and on UTF-8 BYTES:
 *   - parsing annualRate into a double/BigDecimal hides a scale collapse and normalisation;
 *   - a contains("Zoe") assertion on a decoded String also passes when the TEST FILE is mis-encoded.
 *
 * <p>Adapted to this module's harness: the PostgreSQL Dev Services profile and the
 * container-runtime guard shared by the other integration tests. The funnel ordering guard of
 * {@code DraftService} is honoured (the subscriber is captured before any simulation is attached),
 * otherwise a well-formed simulation body would answer 409 and not 200.
 *
 * <p>The field-granularity method asserts the frozen vocabulary document, which is what plan 18
 * owns; the structured {@code problem+json} body carrying those fields is implemented and asserted
 * by plan 22 (see test-gaps.md).
 */
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class WireFormatParityTest {

    @Test
    void rawBodyPreservesDecimalScale() {
        String draftId = simulatedDraft();
        String raw = given().when().get("/v1/drafts/" + draftId)
                .then().statusCode(200).extract().asString();

        assertTrue(raw.contains("\"0.020000\""),
                "annualRate must appear literally as \"0.020000\": the consumer contract types it "
                + "DECIMAL(8,6) and the rate feeds the compounding. Raw body was: " + raw);
        assertTrue(raw.contains("\"1000.00\""), "initialAmount must appear as \"1000.00\"");
        assertTrue(raw.contains("\"100.00\""), "monthlyAmount must appear as \"100.00\"");

        assertFalse(raw.contains("\"0.02\""),
                "a collapsed scale (\"0.02\") breaks the frozen pattern and the consumer schema");
        assertFalse(raw.contains("E-") || raw.contains("E+"),
                "no scientific notation: use toPlainString(), never BigDecimal.toString()");
        assertFalse(raw.matches(".*\"annualRate\"\\s*:\\s*[0-9].*"),
                "annualRate must be a JSON STRING, not a JSON number - a parser would drop the scale");
    }

    @Test
    void frenchFormatsAreRejected() {
        String draftId = subscriberCapturedDraft();

        // The source accepted the comma; the local format now belongs to souscription-web, so the
        // API must refuse it rather than accept both dialects. The separator is composed, never
        // written as a literal in the module.
        String comma = ",";
        given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000" + comma + "00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
            .when().put("/v1/drafts/" + draftId + "/simulation")
            .then().statusCode(400);

        given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
            .when().put("/v1/drafts/" + draftId + "/simulation")
            .then().statusCode(200);

        String other = startDraft();
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Dupont\","
                        + "\"email\":\"claire.dupont@example.org\",\"birthDate\":\"12/05/1990\"}")
            .when().put("/v1/drafts/" + other + "/subscriber")
            .then().statusCode(400);

        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Dupont\","
                        + "\"email\":\"claire.dupont@example.org\",\"birthDate\":\"1990-05-12\"}")
            .when().put("/v1/drafts/" + other + "/subscriber")
            .then().statusCode(200);
    }

    @Test
    void accentedStringsAreValidUtf8() {
        String draftId = startDraft();
        // An acute e on both words, written as escapes so that this test file cannot itself be the
        // source of a mis-encoding.
        String firstName = "Zo\u00e9";
        String lastName = "B\u00e9renger";

        given().contentType("application/json; charset=UTF-8")
                .body(("{\"firstName\":\"" + firstName + "\",\"lastName\":\"" + lastName + "\","
                        + "\"email\":\"zoe.berenger@example.org\",\"birthDate\":\"1990-05-12\"}")
                        .getBytes(StandardCharsets.UTF_8))
            .when().put("/v1/drafts/" + draftId + "/subscriber")
            .then().statusCode(200);

        byte[] body = given().when().get("/v1/drafts/" + draftId)
                .then().statusCode(200).extract().asByteArray();

        assertTrue(indexOf(body, new byte[] {(byte) 0xC3, (byte) 0xA9}) >= 0,
                "the accented e must be the UTF-8 sequence 0xC3 0xA9 on the wire");
        assertFalse(indexOf(body, new byte[] {(byte) 0xC3, (byte) 0x83, (byte) 0xC2, (byte) 0xA9}) >= 0,
                "0xC3 0x83 0xC2 0xA9 is the double-encoding signature (mojibake)");
    }

    @Test
    void unknownPropertyIsRejected() {
        String draftId = subscriberCapturedDraft();
        // Enforces additionalProperties: false - a client typo must not be silently ignored.
        given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10,\"inititalAmount\":\"9999.00\"}")
            .when().put("/v1/drafts/" + draftId + "/simulation")
            .then().statusCode(400);
    }

    @Test
    void validationErrorsKeepFieldGranularityAndOrder() throws IOException {
        String vocabulary = readVocabulary();

        // Source: a LinkedHashMap keyed by field, in declaration order. Every field key must still
        // exist individually - collapsing them loses the information the SPA highlights.
        for (String field : new String[] {"firstName", "lastName", "email", "birthDate",
                "initialAmount", "monthlyAmount", "durationYears", "accepted", "storage"}) {
            assertTrue(vocabulary.contains(field),
                    "each offending field must be reported individually (source: Map<String,String>): "
                    + field + " missing from the frozen vocabulary");
        }
        // The three distinct birth-date outcomes must not be merged into one "invalid date".
        for (String code : new String[] {"BIRTH_DATE_FORMAT", "BIRTH_DATE_FUTURE",
                "BIRTH_DATE_MINOR"}) {
            assertTrue(vocabulary.contains(code), "distinct birthDate outcome missing: " + code);
        }
        int firstName = vocabulary.indexOf("`firstName`, `lastName`");
        assertTrue(firstName >= 0,
                "the declaration order of the error fields must be recorded: the source "
                + "LinkedHashMap made it deterministic and the SPA focuses the first error");

        // Behavioural half of the granularity check that plan 18 can already assert: a malformed
        // birthDate is a structured 400 naming that field, never a 500.
        String draftId = startDraft();
        String raw = given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Dupont\","
                        + "\"email\":\"claire.dupont@example.org\",\"birthDate\":\"2030-13-45\"}")
            .when().put("/v1/drafts/" + draftId + "/subscriber")
            .then().statusCode(400).extract().asString();
        assertTrue(raw.contains("birthDate"),
                "the 400 body must name the offending field; got: " + raw);
    }

    private String readVocabulary() throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("ERROR_VOCABULARY.md")) {
            assertTrue(in != null, "ERROR_VOCABULARY.md must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String startDraft() {
        return given().contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts").then().statusCode(201).extract().path("draftId");
    }

    private String subscriberCapturedDraft() {
        String draftId = startDraft();
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Dupont\","
                        + "\"email\":\"claire.dupont@example.org\",\"birthDate\":\"1990-05-04\"}")
            .when().put("/v1/drafts/" + draftId + "/subscriber").then().statusCode(200);
        return draftId;
    }

    private String simulatedDraft() {
        String draftId = subscriberCapturedDraft();
        given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
            .when().put("/v1/drafts/" + draftId + "/simulation").then().statusCode(200);
        return draftId;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
