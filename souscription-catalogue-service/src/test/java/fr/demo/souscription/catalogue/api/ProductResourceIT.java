package fr.demo.souscription.catalogue.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Traduction REST Assured des assertions HTML du test source
 * {@code src/test/java/fr/demo/souscription/web/SubscriptionIT.java} (lignes 21-42, 121, 134) --
 * W7-T01, levee du blocage CFG-010.
 *
 * Le tableau de tracabilite assertion source -> equivalent JSON (ou reallocation vers
 * {@code souscription-gateway} / {@code souscription-web}) est tenu dans le README du module :
 * aucune assertion source n'est abandonnee sans statut explicite.
 *
 * Aucun port n'est code en dur : {@code @QuarkusTest} porte le cycle de vie du serveur et
 * configure REST Assured (les tests source dependaient d'un Tomcat provisionne par Cargo).
 */
@QuarkusTest
class ProductResourceIT {

    @Test
    void leCatalogueExposeLesTroisPlacements() {
        given().when().get("/v1/products").then()
                .statusCode(200)
                .contentType("application/json;charset=UTF-8")
                .body("size()", is(3))
                .body("code", containsInAnyOrder("PRUDENT", "EQUILIBRE", "DYNAMIQUE"))
                .body("find { it.code == 'EQUILIBRE' }.name", equalTo("Horizon Équilibre"))
                .header("ETag", notNullValue())
                .header("Cache-Control", containsString("max-age="))
                .body(matchesJsonSchemaInClasspath("schema/product-summary-list.json"));
    }

    @Test
    void laFicheProduitRespecteLeSchemaGele() {
        given().when().get("/v1/products/EQUILIBRE").then()
                .statusCode(200)
                .contentType("application/json;charset=UTF-8")
                .body("code", equalTo("EQUILIBRE"))
                .body("name", equalTo("Horizon Équilibre"))
                .header("ETag", notNullValue())
                .header("Cache-Control", containsString("max-age="))
                .body(matchesJsonSchemaInClasspath("schema/product-detail.json"));
    }

    @Test
    void unCodeInconnuRenvoieUn404Problem() {
        given().when().get("/v1/products/INCONNU").then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("status", is(404));
    }

    @Test
    void unCodeEnMinusculesRenvoieUn404Problem() {
        given().when().get("/v1/products/equilibre").then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("status", is(404));
    }

    @Test
    void unCodeAvecEspacesRenvoieUn404Problem() {
        given().when().get("/v1/products/%20EQUILIBRE%20").then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("status", is(404));
    }

    @Test
    void unCodeVideRenvoieUn404Problem() {
        given().when().get("/v1/products/").then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("status", is(404));
    }

    @Test
    void lePostSurLeCatalogueRenvoieUn405Problem() {
        given().when().post("/v1/products").then()
                .statusCode(405)
                .contentType("application/problem+json")
                .body("status", is(405));
    }
}
