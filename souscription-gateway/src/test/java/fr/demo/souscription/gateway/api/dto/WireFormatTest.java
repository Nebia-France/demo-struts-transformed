package fr.demo.souscription.gateway.api.dto;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Round-trip guarantees of the public wire format (W1-T02).
 *
 * <p>Every assertion is made on the raw serialised text, and on the bytes for the accented label:
 * parsing a rate to a number hides a collapsed scale ({@code 0.02} instead of the contracted
 * {@code 0.020000}) and decoding a label to a {@code String} hides a double-encoding defect.
 * Reference scales come from {@code src/main/resources/db/schema.sql}: amounts
 * {@code DECIMAL(14,2)}, annual rate {@code DECIMAL(8,6)}.
 */
@QuarkusTest
class WireFormatTest {

    @Inject
    ObjectMapper mapper;

    @Test
    void annualRateKeepsScaleSixOnTheWire() throws Exception {
        String json = mapper.writeValueAsString(
                new ProductSummary("PRUDENT", "Horizon Prudent", "Prudent", new BigDecimal("0.02")));

        assertTrue(json.contains("\"0.020000\""), "rate must be the string 0.020000: " + json);
        assertFalse(json.contains("\"annualRate\":0.02"), "rate must not collapse to 0.02");
        assertFalse(json.matches(".*[0-9]E[+-].*"), "no scientific notation allowed: " + json);
    }

    @Test
    void amountsKeepScaleTwoOnTheWire() throws Exception {
        String json = mapper.writeValueAsString(new SimulationResult(
                new BigDecimal("1000"), new BigDecimal("100"), 5, new BigDecimal("7000"),
                new BigDecimal("7123.4")));

        assertTrue(json.contains("\"1000.00\""), "initial amount must be 1000.00: " + json);
        assertTrue(json.contains("\"100.00\""), "monthly amount must be 100.00: " + json);
        assertTrue(json.contains("\"7123.40\""), "final capital must be 7123.40: " + json);
    }

    @Test
    void numericValueSurvivesTheRoundTrip() throws Exception {
        BigDecimal source = new BigDecimal("0.06");
        String json = mapper.writeValueAsString(
                new ProductSummary("DYNAMIQUE", "Horizon Dynamique", "Dynamique", source));
        ProductSummary back = mapper.readValue(json, ProductSummary.class);

        // compareTo, not equals: 0.06 and 0.060000 are numerically equal, which is the point.
        assertEquals(0, back.annualRate().compareTo(source),
                "the rate VALUE must be preserved, only its scale is strengthened");
    }

    @Test
    void amountSurvivesTheRoundTrip() throws Exception {
        SimulationInput source = new SimulationInput(
                new BigDecimal("1234.5"), new BigDecimal("50"), 12);
        SimulationInput back = mapper.readValue(mapper.writeValueAsString(source),
                SimulationInput.class);

        assertEquals(0, back.initialAmount().compareTo(source.initialAmount()));
        assertEquals(0, back.monthlyAmount().compareTo(source.monthlyAmount()));
        assertEquals(12, back.durationYears());
    }

    @Test
    void datesAreIso8601AndNotLocaleFormatted() throws Exception {
        String json = mapper.writeValueAsString(
                new SubscriberInput("Camille", "Martin", "camille@example.test",
                        LocalDate.of(1980, 1, 1)));

        assertTrue(json.contains("\"1980-01-01\""), "date must be ISO-8601: " + json);
        assertFalse(json.contains("01/01/1980"), "the localised fr_FR rendering belongs to the SPA");

        SubscriberInput back = mapper.readValue(json, SubscriberInput.class);
        assertEquals(LocalDate.of(1980, 1, 1), back.birthDate());
    }

    @Test
    void instantsAreIso8601AndNotTimestamps() throws Exception {
        String json = mapper.writeValueAsString(new SubscriptionConfirmation(
                "REF-1",
                new ProductSummary("PRUDENT", "Horizon Prudent", "Prudent", new BigDecimal("0.02")),
                new SubscriberInput("Camille", "Martin", "camille@example.test",
                        LocalDate.of(1980, 1, 1)),
                new SimulationResult(new BigDecimal("1000"), new BigDecimal("100"), 5,
                        new BigDecimal("7123.4")),
                Instant.parse("2025-01-01T10:00:00Z")));

        assertTrue(json.contains("\"2025-01-01T10:00:00Z\""), "instant must be ISO-8601: " + json);
        assertFalse(json.contains("1735725600"), "an instant must not be a numeric timestamp");
    }

    @Test
    void nullMembersAreOmitted() throws Exception {
        String json = mapper.writeValueAsString(new DraftView("d-1", null, null, null, "k-1",
                "STARTED"));

        assertFalse(json.contains("subscriber"), "serialization-inclusion=non-null: " + json);
        assertFalse(json.contains("simulation"), "serialization-inclusion=non-null: " + json);
    }

    @Test
    void accentedLabelKeepsItsExactUtf8Bytes() throws Exception {
        String json = mapper.writeValueAsString(new ProductSummary(
                "EQUILIBRE", "Horizon \u00c9quilibre", "\u00c9quilibr\u00e9",
                new BigDecimal("0.04")));
        String hex = toHex(json.getBytes(StandardCharsets.UTF_8));

        assertTrue(hex.contains("c389"), "E-acute must be the UTF-8 sequence 0xC3 0x89");
        assertFalse(hex.contains("c383"), "0xC3 0x83 is the double-encoding signature (mojibake)");
    }

    @Test
    void accentedLabelSurvivesTheHttpRoundTrip() {
        byte[] body = given().when().get("/api/v1/products/EQUILIBRE")
                .then().statusCode(200)
                .extract().asByteArray();
        String hex = toHex(body);

        assertTrue(hex.contains("c389"), "E-acute must reach the client as 0xC3 0x89");
        assertFalse(hex.contains("c383"), "0xC3 0x83 is the double-encoding signature (mojibake)");
    }

    @Test
    void unknownJsonPropertyIsRejectedWith400() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("productCode", "PRUDENT");
        payload.put("initialAmount", "1000.00");
        payload.put("monthlyAmount", "100.00");
        payload.put("durationYears", 10);
        payload.put("unexpected", 1);

        fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf().contentType(ContentType.JSON).body(payload)
                .when().post("/api/v1/simulations")
                .then().statusCode(400);
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
