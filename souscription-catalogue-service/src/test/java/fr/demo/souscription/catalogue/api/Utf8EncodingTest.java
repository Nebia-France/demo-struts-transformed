package fr.demo.souscription.catalogue.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end UTF-8 for the accented French labels (W4-T02, CFG-009, TG-004).
 *
 * Assertions are made on the byte sequence, not only on a decoded string: a double encoding
 * ("Ã‰quilibre") silently satisfies a contains("Équilibre") assertion when the test source file
 * itself is mis-encoded, so the raw 0xC3 0x89 check is mandatory.
 */
@QuarkusTest
class Utf8EncodingTest {

    private static final byte[] E_AIGU_MAJUSCULE = { (byte) 0xC3, (byte) 0x89 };

    private byte[] body(String path) {
        return given().when().get(path).then()
                .statusCode(200)
                .contentType("application/json;charset=UTF-8")
                .extract().asByteArray();
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

    @Test
    void lesLibellesAccentuesSontEnUtf8() {
        byte[] body = body("/v1/products");
        assertTrue(indexOf(body, E_AIGU_MAJUSCULE) >= 0,
                "la liste doit porter 'E' accentue encode 0xC3 0x89");
        String decoded = new String(body, StandardCharsets.UTF_8);
        assertTrue(decoded.contains("Horizon Équilibre"), decoded);
        assertTrue(decoded.contains("Équilibré"), decoded);
    }

    @Test
    void laFicheProduitPorteLesSixChainesAccentuees() {
        byte[] body = body("/v1/products/EQUILIBRE");
        assertTrue(indexOf(body, E_AIGU_MAJUSCULE) >= 0,
                "la fiche doit porter 'E' accentue encode 0xC3 0x89");
        String decoded = new String(body, StandardCharsets.UTF_8);
        assertTrue(decoded.contains("Horizon Équilibre"), decoded);
        assertTrue(decoded.contains("Équilibré"), decoded);
        assertTrue(decoded.contains("hypothèse"), decoded);
        assertTrue(decoded.contains("durée"), decoded);

        String prudent = new String(body("/v1/products/PRUDENT"), StandardCharsets.UTF_8);
        assertTrue(prudent.contains("modérée"), prudent);

        String dynamique = new String(body("/v1/products/DYNAMIQUE"), StandardCharsets.UTF_8);
        assertTrue(dynamique.contains("élevée"), dynamique);
        assertTrue(dynamique.contains("associée"), dynamique);
    }

    @Test
    void aucunDoubleEncodageDansLaReponse() {
        String decoded = new String(body("/v1/products"), StandardCharsets.UTF_8);
        assertTrue(!decoded.contains("Ã"), "double encodage detecte: " + decoded);
    }
}
