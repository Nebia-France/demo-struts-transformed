package fr.demo.souscription.catalogue.api;

// Parity-test skeleton for 18-souscription-catalogue-rfc7807-problem-json-parity.
// Copy to souscription-catalogue-service/src/test/java/fr/demo/souscription/catalogue/api/.
//
// Source reference: action/CatalogAction.java -> response.sendError(404) / sendError(405),
// rendered by the seven <error-page> blocks of web.xml through jsp/erreur.jsp, which showed
// one user-facing message per code and never a technical trace.

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class ProblemJsonParityTest {

    @ParameterizedTest
    @ValueSource(strings = {"INEXISTANT", "prudent", "%20PRUDENT%20", "PRUDENT123", ""})
    void les404SourceSontConservesAvecUnCorpsProblemJson(String code) {
        given().when().get("/v1/products/" + code).then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("status", is(404))
                .body("type", notNullValue())
                .body("title", notNullValue())
                .body("detail", notNullValue());
    }

    @Test
    void le405SourceEstConserveAvecUnCorpsProblemJson() {
        given().when().post("/v1/products").then()
                .statusCode(405)
                .contentType("application/problem+json")
                .body("status", is(405));
    }

    @Test
    void leCodeDemandeNEstJamaisReflechiDansLeCorps() {
        String body = given().when().get("/v1/products/ZZZPAYLOAD").then()
                .statusCode(404).extract().asString();
        assertFalse(body.contains("ZZZPAYLOAD"), body);
    }

    @Test
    void aucunDetailInterneNEstDivulgue() {
        String body = given().when().get("/v1/products/INEXISTANT").then()
                .statusCode(404).extract().asString().toLowerCase();
        assertFalse(body.contains("exception"), body);
        assertFalse(body.contains("stack"), body);
        assertFalse(body.contains("fr.demo"), body);
        assertFalse(body.contains("caused by"), body);
    }
}
