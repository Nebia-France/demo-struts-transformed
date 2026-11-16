package fr.demo.souscription.gateway.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity skeleton for 08-souscription-gateway-wire-dtos.
 *
 * Every assertion is made on the RAW serialised text (and on bytes for the accented label).
 * Parsing a rate to double hides a collapsed scale (0.02 vs the contracted 0.020000) and
 * decoding a label to a String hides a double-encoding defect, so value-level assertions would
 * pass on a corrupted wire representation.
 *
 * Reference scales come from src/main/resources/db/schema.sql: amounts DECIMAL(14,2),
 * annual rate DECIMAL(8,6). The shared schemas require both to travel as JSON strings.
 */
@QuarkusTest
class WireFormatParityTest {

    @Inject
    ObjectMapper mapper;

    @Test
    void annualRateKeepsScaleSixOnTheWire() throws Exception {
        String json = mapper.writeValueAsString(
                new ProductSummary("PRUDENT", "Horizon Prudent", new BigDecimal("0.02")));

        assertTrue(json.contains("\"0.020000\""),
                "rate must be the string 0.020000 (scale 6), body was: " + json);
        assertFalse(json.contains("\"annualRate\":0.02"), "rate must not collapse to 0.02");
        assertFalse(json.matches(".*[0-9]E[+-].*"), "no scientific notation allowed: " + json);
    }

    @Test
    void amountsKeepScaleTwoOnTheWire() throws Exception {
        String json = mapper.writeValueAsString(new SimulationResult(
                new BigDecimal("1000"), new BigDecimal("100"), 5, new BigDecimal("7123.4")));

        assertTrue(json.contains("\"1000.00\""), "initial payment must be 1000.00, body: " + json);
        assertTrue(json.contains("\"100.00\""), "monthly payment must be 100.00, body: " + json);
        assertTrue(json.contains("\"7123.40\""), "final capital must be 7123.40, body: " + json);
    }

    @Test
    void numericValueSurvivesTheRoundTrip() throws Exception {
        BigDecimal source = new BigDecimal("0.06");
        String json = mapper.writeValueAsString(
                new ProductSummary("DYNAMIQUE", "Horizon Dynamique", source));
        ProductSummary back = mapper.readValue(json, ProductSummary.class);

        // compareTo, not equals: 0.06 and 0.060000 are numerically equal, which is the point.
        assertEquals(0, back.annualRate().compareTo(source),
                "the rate VALUE must be preserved, only its scale is strengthened");
    }

    @Test
    void datesAreIso8601AndNotLocaleFormatted() throws Exception {
        String json = mapper.writeValueAsString(
                new SubscriberInput("Camille", "Martin", "camille@example.test",
                        LocalDate.of(1980, 1, 1)));

        assertTrue(json.contains("\"1980-01-01\""), "date must be ISO-8601, body: " + json);
        assertFalse(json.contains("01/01/1980"), "the fr_FR dd/MM/yyyy rendering belongs to the SPA");
        assertFalse(json.contains("315"), "a date must not be serialised as a timestamp");
    }

    @Test
    void accentedLabelKeepsItsExactUtf8Bytes() throws Exception {
        String json = mapper.writeValueAsString(
                new ProductSummary("EQUILIBRE", "Horizon \u00c9quilibre", new BigDecimal("0.04")));
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        String bytes = toHex(raw);

        assertTrue(bytes.contains("c389"), "E-acute must be the UTF-8 sequence 0xC3 0x89");
        assertFalse(bytes.contains("c383"), "0xC3 0x83 is the double-encoding signature (mojibake)");
    }

    @Test
    void unknownJsonPropertyIsRejected() {
        assertDoesNotThrow(() -> {
            try {
                mapper.readValue("{\"code\":\"PRUDENT\",\"unexpected\":1}", ProductSummary.class);
                throw new AssertionError("fail-on-unknown-properties=true must reject the payload");
            } catch (AssertionError e) {
                throw e;
            } catch (Exception expected) {
                // the strict setting rejected it: this is the deliberate hardening (NFG-006)
            }
        });
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
