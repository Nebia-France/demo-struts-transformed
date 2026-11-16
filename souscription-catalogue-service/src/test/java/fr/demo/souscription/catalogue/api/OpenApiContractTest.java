package fr.demo.souscription.catalogue.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class OpenApiContractTest {

    @Test
    void leContratV1EstGele() throws Exception {
        String served = given().accept("application/yaml")
                .when().get("/q/openapi").then().statusCode(200)
                .extract().asString();
        String frozen = Files.readString(
                Path.of("src/main/resources/META-INF/openapi-contract-v1.yaml"), StandardCharsets.UTF_8);
        assertEquals(frozen.strip(), served.strip());
    }
}
