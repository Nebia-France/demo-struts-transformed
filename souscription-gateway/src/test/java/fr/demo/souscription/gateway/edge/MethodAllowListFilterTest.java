package fr.demo.souscription.gateway.edge;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import fr.demo.souscription.gateway.observability.RejectionMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * W2-T02: the method allow-list of the source {@code SecurityFilter} must reject every method
 * outside {@code gateway.security.allowed-methods} with 405, an {@code Allow} header and a
 * {@code problem+json} body — the source produced HTML through {@code erreur.jsp}.
 */
@QuarkusTest
class MethodAllowListFilterTest {

    @Inject
    MeterRegistry registry;

    private RejectionObservation observation;

    @BeforeEach
    void observe() {
        observation = RejectionObservation.start(registry);
    }

    @AfterEach
    void release() {
        observation.stop();
    }

    private static String expectedAllow() {
        return String.join(", ", ConfigProvider.getConfig()
                .getValue("gateway.security.allowed-methods", String[].class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "PATCH", "OPTIONS", "TRACE"})
    void disallowedMethodsAreRejected(String method) {
        double before = observation.count(405, RejectionMetrics.METHOD);
        String instance = given().when().request(method, "/api/v1/products")
                .then().statusCode(405)
                .header("Allow", expectedAllow())
                .contentType("application/problem+json")
                .body("status", equalTo(405))
                .body("title", notNullValue())
                .body("type", containsString("method-not-allowed"))
                .extract().jsonPath().getString("instance");
        observation.assertSingleRejection(405, RejectionMetrics.METHOD, before, instance);
    }

    /**
     * Each allowed method is sent to a route that declares it, so a 405 could only come from the
     * allow-list filter. The other statuses (400, 415, 502...) are irrelevant here.
     */
    @ParameterizedTest
    @CsvSource({
        "GET, /api/v1/products",
        "HEAD, /api/v1/products",
        "POST, /api/v1/drafts",
        "PUT, /api/v1/drafts/unknown-draft/subscriber"
    })
    void allowedMethodsAreNotRejectedByTheFilter(String method, String path) {
        given().when().request(method, path)
                .then().statusCode(not(405));
    }

    @Test
    void theRejectionStillCarriesTheSecurityHeaders() {
        double before = observation.count(405, RejectionMetrics.METHOD);
        String instance = given().when().request("DELETE", "/api/v1/products")
                .then().statusCode(405)
                .header("Cache-Control", "no-store")
                .header("X-Frame-Options", "DENY")
                .extract().jsonPath().getString("instance");
        observation.assertSingleRejection(405, RejectionMetrics.METHOD, before, instance);
    }
}
