package fr.demo.souscription.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity check for the golden file (plan 19): it must freeze the corrected representation of the
 * source business data, not a defect. Verifies the product count, the source order, the accented
 * labels, the 6-decimal rates and the absence of any presentation field.
 */
class GoldenFileParityTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden/products-v1.json");

    private static String raw;
    private static JsonNode products;

    @BeforeAll
    static void readGoldenFile() throws Exception {
        raw = Files.readString(GOLDEN, StandardCharsets.UTF_8);
        products = new ObjectMapper().readTree(raw);
    }

    @Test
    void leFichierGoldenContientLesTroisProduitsDansLOrdreSource() {
        assertTrue(products.isArray(), "le golden doit etre un tableau JSON");
        assertEquals(3, products.size());
        assertEquals(List.of("PRUDENT", "EQUILIBRE", "DYNAMIQUE"),
                List.of(products.get(0).get("code").asText(),
                        products.get(1).get("code").asText(),
                        products.get(2).get("code").asText()));
    }

    @Test
    void lesLibellesAccentuesSontPreservesEnUtf8() {
        assertEquals("Horizon Équilibre", products.get(1).get("name").asText());
        assertEquals("Équilibré", products.get(1).get("profile").asText());
    }

    @Test
    void lesTauxSontFigesAvecSixDecimales() {
        assertTrue(raw.contains("0.020000"), "taux PRUDENT a 6 decimales");
        assertTrue(raw.contains("0.040000"), "taux EQUILIBRE a 6 decimales");
        assertTrue(raw.contains("0.060000"), "taux DYNAMIQUE a 6 decimales");
    }

    @Test
    void aucunChampDePresentationNEstPublie() {
        for (JsonNode product : products) {
            assertFalse(product.has("step"), "champ de presentation 'step' publie");
            assertFalse(product.has("pageTitle"), "champ de presentation 'pageTitle' publie");
            java.util.List<String> fields = new java.util.ArrayList<>();
            product.fieldNames().forEachRemaining(fields::add);
            assertEquals(List.of("code", "name", "profile", "annualRate"), fields);
        }
    }
}
