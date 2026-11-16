package fr.demo.souscription.catalogue.api;

// Parity-test skeleton for 17-souscription-catalogue-utf8-end-to-end-parity.
// Copy to souscription-catalogue-service/src/test/java/fr/demo/souscription/catalogue/api/.
//
// Source reference: SubscriptionIT asserted html.contains("Horizon Équilibre") on a response
// whose encoding was forced by SecurityFilter + web.xml <page-encoding>UTF-8</page-encoding>.
// Assertions are on BYTES: a double encoding ("Ã‰quilibre") satisfies a decoded-string
// assertion when the test file itself is mis-encoded.

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Utf8WireParityTest {

    private static final byte[] E_ACUTE_UPPER = { (byte) 0xC3, (byte) 0x89 };   // É
    private static final byte[] E_ACUTE_LOWER = { (byte) 0xC3, (byte) 0xA9 };   // é
    private static final byte[] DOUBLE_ENCODED = { (byte) 0xC3, (byte) 0x83 };  // Ã signature

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

    private static byte[] bytesOf(String path) {
        return given().when().get(path).then()
                .statusCode(200)
                .contentType("application/json;charset=UTF-8")
                .extract().asByteArray();
    }

    @Test
    void lesLibellesAccentuesSontEnUtf8SurLaListe() {
        byte[] body = bytesOf("/v1/products");

        assertTrue(indexOf(body, E_ACUTE_UPPER) >= 0, "0xC3 0x89 (É) absent du corps");
        assertTrue(indexOf(body, E_ACUTE_LOWER) >= 0, "0xC3 0xA9 (é) absent du corps");
        assertFalse(indexOf(body, DOUBLE_ENCODED) >= 0, "signature de double encodage detectee");

        String decoded = new String(body, StandardCharsets.UTF_8);
        assertTrue(decoded.contains("Horizon Équilibre"), decoded);
        assertTrue(decoded.contains("Équilibré"), decoded);
    }

    @Test
    void lesDescriptionsAccentueesSontEnUtf8SurLaFiche() {
        for (String code : new String[] { "PRUDENT", "EQUILIBRE", "DYNAMIQUE" }) {
            byte[] body = bytesOf("/v1/products/" + code);
            assertFalse(indexOf(body, DOUBLE_ENCODED) >= 0, "double encodage sur " + code);
        }

        String prudent = new String(bytesOf("/v1/products/PRUDENT"), StandardCharsets.UTF_8);
        assertTrue(prudent.contains("modérée"), prudent);
        assertTrue(prudent.contains("hypothèse"), prudent);

        String equilibre = new String(bytesOf("/v1/products/EQUILIBRE"), StandardCharsets.UTF_8);
        assertTrue(equilibre.contains("durée"), equilibre);

        String dynamique = new String(bytesOf("/v1/products/DYNAMIQUE"), StandardCharsets.UTF_8);
        assertTrue(dynamique.contains("élevée"), dynamique);
        assertTrue(dynamique.contains("associée"), dynamique);
    }
}
