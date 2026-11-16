package fr.demo.souscription.catalogue.api;

// Parity-test skeleton for 29-souscription-catalogue-http-cache-etag-parity.
// Copy to souscription-catalogue-service/src/test/java/fr/demo/souscription/catalogue/api/.
//
// Source reference: SubscriptionIT line 32 asserted Cache-Control == "no-store" on the catalogue
// page. The target serves "max-age=300, public" + ETag: an ACCEPTED contract change recorded in
// the plan-31 traceability table. The invariant that must NOT change is the payload itself.

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class HttpCacheParityTest {

    private static String etagOf(String path) {
        return given().when().get(path).then().statusCode(200)
                .header("ETag", notNullValue())
                .extract().header("ETag");
    }

    @Test
    void lesDeuxEndpointsPortentEtagEtCacheControl() {
        for (String path : new String[] { "/v1/products", "/v1/products/PRUDENT" }) {
            given().when().get(path).then()
                    .statusCode(200)
                    .header("ETag", notNullValue())
                    .header("Cache-Control", containsString("max-age="))
                    .header("Cache-Control", containsString("public"));
        }
    }

    @Test
    void laRevalidationRenvoieUn304SansCorps() {
        String etag = etagOf("/v1/products");

        String body = given().header("If-None-Match", etag)
                .when().get("/v1/products").then()
                .statusCode(304)
                .extract().asString();
        assertTrue(body == null || body.isEmpty(), "le 304 ne doit porter aucun corps");
    }

    @Test
    void unEtagNonCorrespondantRenvoieLeCorpsComplet() {
        given().header("If-None-Match", "\"etag-obsolete\"")
                .when().get("/v1/products").then()
                .statusCode(200)
                .body("size()", is(3));
    }

    @Test
    void lEtagEstStableEntreDeuxAppels() {
        assertEquals(etagOf("/v1/products"), etagOf("/v1/products"),
                "ETag recalcule par requete au lieu d'etre calcule a l'amorcage");
        assertEquals(etagOf("/v1/products/PRUDENT"), etagOf("/v1/products/PRUDENT"));
    }

    @Test
    void lesCodesInconnusRestentEn404MalgreLesPreconditions() {
        given().header("If-None-Match", etagOf("/v1/products"))
                .when().get("/v1/products/INEXISTANT").then()
                .statusCode(404);
    }
}
