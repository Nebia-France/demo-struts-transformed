package fr.demo.souscription.catalogue.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity on the raw JSON text, never on a parsed number: parsing to a double would hide exactly
 * the defect this test exists to catch. Verifies numeric equality with the source values
 * (0.02 / 0.04 / 0.06) and the stronger representation guarantee introduced by plan 16
 * (scale 6 on annualRate, scale 2 on ratePercent, no scientific notation, no normalisation).
 */
@QuarkusTest
class BigDecimalWireParityTest {

    private static final Pattern ANNUAL_RATE = Pattern.compile("\"annualRate\":(-?[0-9.]+)");

    private String body(String path) {
        return given().when().get(path).then().statusCode(200).extract().asString();
    }

    @Test
    void laFicheProduitPorteLesLitterauxExacts() {
        String body = body("/v1/products/DYNAMIQUE");
        assertTrue(body.contains("\"annualRate\":0.060000"), body);
        assertTrue(body.contains("\"ratePercent\":6.00"), body);
        assertFalse(body.contains("\"annualRate\":\""), "annualRate ne doit pas etre une chaine: " + body);
    }

    @Test
    void laListePorteLesTroisTauxAvecSixDecimales() {
        String body = body("/v1/products");
        assertTrue(body.contains("\"annualRate\":0.020000"), body);
        assertTrue(body.contains("\"annualRate\":0.040000"), body);
        assertTrue(body.contains("\"annualRate\":0.060000"), body);
    }

    @Test
    void lesTauxSontNumeriquementEgauxAuxValeursSource() {
        String body = body("/v1/products");
        List<BigDecimal> expected = List.of(new BigDecimal("0.02"), new BigDecimal("0.04"),
                                            new BigDecimal("0.06"));
        Matcher matcher = ANNUAL_RATE.matcher(body);
        for (BigDecimal source : expected) {
            assertTrue(matcher.find(), "taux manquant dans " + body);
            BigDecimal wire = new BigDecimal(matcher.group(1));
            assertEquals(0, wire.compareTo(source), "attendu " + source + " recu " + wire);
            assertEquals(6, wire.scale(), "scale attendu 6 pour " + wire);
        }
        assertFalse(matcher.find(), "plus de trois produits dans " + body);
    }

    @Test
    void leRatePercentVautCentFoisLeTauxAnnuel() {
        for (String code : List.of("PRUDENT", "EQUILIBRE", "DYNAMIQUE")) {
            String body = body("/v1/products/" + code);
            Matcher annual = ANNUAL_RATE.matcher(body);
            assertTrue(annual.find(), body);
            BigDecimal rate = new BigDecimal(annual.group(1));
            Matcher percent = Pattern.compile("\"ratePercent\":(-?[0-9.]+)").matcher(body);
            assertTrue(percent.find(), body);
            BigDecimal wirePercent = new BigDecimal(percent.group(1));
            assertEquals(0, wirePercent.compareTo(rate.multiply(new BigDecimal("100"))), body);
            assertEquals(2, wirePercent.scale(), "scale attendu 2 pour " + wirePercent);
        }
    }

    @Test
    void aucuneNotationScientifiqueNiScaleEffondre() {
        for (String path : List.of("/v1/products", "/v1/products/PRUDENT",
                                   "/v1/products/EQUILIBRE", "/v1/products/DYNAMIQUE")) {
            String body = body(path);
            assertFalse(body.contains("E+"), body);
            assertFalse(body.contains("E-"), body);
            assertFalse(Pattern.compile("\"annualRate\":0\\.0[246](?!0)").matcher(body).find(), body);
        }
    }
}
