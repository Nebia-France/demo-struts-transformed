package fr.demo.souscription.catalogue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Freezes the published catalogue representation (W4-T04). The annual rate is an auditable
 * business figure (CFG-011): any rate or description change must come with an explicit,
 * reviewable update of src/test/resources/golden/products-v1.json.
 */
@QuarkusTest
class CatalogueGoldenFileTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden/products-v1.json");

    @Test
    void laRepresentationPublieeDuCatalogueEstFigee() throws Exception {
        String actual = new String(given().when().get("/v1/products").asByteArray(),
                StandardCharsets.UTF_8);
        String expected = Files.readString(GOLDEN, StandardCharsets.UTF_8);
        assertEquals(expected.strip(), actual.strip());
    }
}
