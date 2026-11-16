package fr.demo.souscription.catalogue.api;

// Parity-test skeleton for 08-souscription-catalogue-rest-resource-v1-parity.
// Copy to souscription-catalogue-service/src/test/java/fr/demo/souscription/catalogue/api/.
//
// Source reference: src/main/java/fr/demo/souscription/action/CatalogAction.java
//   if ("POST".equals(request.getMethod())) { response.sendError(405); return null; }
//   if (!product.isPresent()) { response.sendError(404); return null; }
// Gherkin: "Consultation de la liste des placements", "Consultation de la fiche d'un placement
// inconnu", "Code produit vide ou mal formate sur la fiche produit",
// "Methode HTTP non autorisee sur le catalogue".

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class ProductResourceParityTest {

    @Test
    void laListeExposeLesTroisPlacements() {
        given().when().get("/v1/products").then()
                .statusCode(200)
                .body("size()", is(3))
                .body("code", containsInAnyOrder("PRUDENT", "EQUILIBRE", "DYNAMIQUE"));
    }

    @Test
    void laFicheProduitExposeLesSixChamps() {
        given().when().get("/v1/products/PRUDENT").then()
                .statusCode(200)
                .body("code", equalTo("PRUDENT"))
                .body("name", equalTo("Horizon Prudent"))
                .body("profile", equalTo("Prudent"))
                .body("description", notNullValue())
                .body("annualRate", notNullValue())
                .body("ratePercent", notNullValue());
    }

    // The five source 404 cases: unknown, lowercase, padded, suffixed, empty.
    @ParameterizedTest
    @ValueSource(strings = {"INEXISTANT", "prudent", "%20PRUDENT%20", "PRUDENT123", ""})
    void unCodeNonConformeRenvoie404(String code) {
        given().when().get("/v1/products/" + code).then().statusCode(404);
    }

    @Test
    void lePostSurLeCatalogueRenvoie405() {
        given().when().post("/v1/products").then().statusCode(405);
    }

    @Test
    void aucuneDonneeDePresentationNEstPubliee() {
        // step and pageTitle belong to souscription-web (FG-008)
        given().when().get("/v1/products/PRUDENT").then()
                .statusCode(200)
                .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("step")))
                .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("pageTitle")));
    }
}
