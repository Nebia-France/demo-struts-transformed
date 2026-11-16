package fr.demo.souscription.catalogue.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ProductSerializationTest {

    @Test
    void leTauxAnnuelEstSerialiseAvecSixDecimales() {
        String body = given().when().get("/v1/products/DYNAMIQUE").then()
                .statusCode(200).extract().asString();
        assertTrue(body.contains("\"annualRate\":0.060000"), body);
        assertTrue(body.contains("\"ratePercent\":6.00"), body);
    }

    @Test
    void laListeNUtiliseNiNotationScientifiqueNiNormalisationDeScale() {
        String body = given().when().get("/v1/products").then()
                .statusCode(200).extract().asString();
        assertTrue(body.contains("0.020000"), body);
        assertFalse(body.contains("E-"), body);
        assertFalse(body.contains("E+"), body);
    }
}
