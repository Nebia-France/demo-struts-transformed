package fr.demo.souscription.simulation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.net.URL;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Swagger UI est une surface non authentifiée : elle n'est activée que par
 * quarkus.swagger-ui.always-include, positionné à false par défaut et à true dans les
 * seuls profils dev et test (W5-T06). Le profil prod hérite donc du défaut false et
 * /q/swagger-ui répond 404.
 */
@QuarkusTest
class SwaggerUiProfileTest {

    @ConfigProperty(name = "quarkus.swagger-ui.always-include")
    boolean alwaysInclude;

    @TestHTTPResource(value = "swagger-ui", management = true)
    URL managementSwaggerUi;

    @Test
    void uiIsIncludedInTheTestProfile() {
        assertTrue(alwaysInclude, "quarkus.swagger-ui.always-include doit être true en test");
        Response response = RestAssured.get(managementSwaggerUi.toString());
        assertEquals(200, response.statusCode());
    }
}
