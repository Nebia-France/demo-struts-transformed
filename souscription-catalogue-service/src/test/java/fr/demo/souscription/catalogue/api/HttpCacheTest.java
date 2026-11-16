package fr.demo.souscription.catalogue.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP caching of the read-only catalogue (W6-T04): every 200 on /v1 carries an ETag computed
 * once at seeding plus Cache-Control, and a matching If-None-Match gets a bodiless 304.
 */
@QuarkusTest
class HttpCacheTest {

    @Test
    void laListeExposeEtagEtCacheControl() {
        String etag = given().when().get("/v1/products").then().statusCode(200)
                .extract().header("ETag");
        assertNotNull(etag);
        String cacheControl = given().when().get("/v1/products").then().statusCode(200)
                .extract().header("Cache-Control");
        assertTrue(cacheControl.contains("max-age=300"), cacheControl);
        assertTrue(cacheControl.contains("public"), cacheControl);
    }

    @Test
    void lEtagEstStableEntreDeuxAppels() {
        String first = given().when().get("/v1/products").then().extract().header("ETag");
        String second = given().when().get("/v1/products").then().extract().header("ETag");
        assertEquals(first, second);
    }

    @Test
    void laRevalidationDeLaListeRetourne304SansCorps() {
        String etag = given().when().get("/v1/products").then().extract().header("ETag");
        byte[] body = given().header("If-None-Match", etag)
                .when().get("/v1/products").then().statusCode(304)
                .extract().asByteArray();
        assertEquals(0, body.length);
    }

    @Test
    void laFicheExposeEtagEtRevalide() {
        String etag = given().when().get("/v1/products/PRUDENT").then().statusCode(200)
                .extract().header("ETag");
        assertNotNull(etag);
        byte[] body = given().header("If-None-Match", etag)
                .when().get("/v1/products/PRUDENT").then().statusCode(304)
                .extract().asByteArray();
        assertEquals(0, body.length);
    }
}
