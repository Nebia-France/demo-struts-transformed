package fr.demo.souscription.gateway.edge;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * W2-T01: the five security response headers of the source {@code SecurityFilter} must be present on
 * every response, including those produced outside JAX-RS.
 */
@QuarkusTest
class SecurityHeaderFilterTest {

    private static String expectedCsp() {
        return ConfigProvider.getConfig().getValue("gateway.security.csp", String.class);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "/api/v1/products, 200",
        "/nonexistent, 404",
        "/test-only/failures/not-found, 404",
        "/test-only/failures/boom, 500"
    })
    void allFiveHeadersArePresent(String path, int expectedStatus) {
        given().when().get(path)
                .then().statusCode(expectedStatus)
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", expectedCsp())
                .header("Cache-Control", "no-store");
    }

    @Test
    void theConfiguredCspIsRestrictive() {
        String csp = expectedCsp();
        assertFalse(csp.contains("unsafe-inline"), "CSP must not allow inline content: " + csp);
        assertFalse(csp.contains("*"), "CSP must not use a wildcard source: " + csp);
    }

    @Test
    void managementStyleUnmatchedPathIsAlsoCovered() {
        given().when().get("/q/nonexistent")
                .then().header("Content-Security-Policy", expectedCsp())
                .header("Cache-Control", "no-store");
    }
}
