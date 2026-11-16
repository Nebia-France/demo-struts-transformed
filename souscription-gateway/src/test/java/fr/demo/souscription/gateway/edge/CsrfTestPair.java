package fr.demo.souscription.gateway.edge;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Test helper producing a request already carrying a valid CSRF double-submit pair (W3-T03).
 *
 * <p>Since {@link CsrfDoubleSubmitFilter} enforces the check on every state-changing request of
 * {@code /api/v1}, the tests that exercise those routes for another reason (routing, wire format,
 * input bounds, problem+json shape) must present a valid pair; otherwise they would assert on a
 * 403 produced at the edge. Tests that assert the rejection itself live in {@code CsrfMatrixTest}
 * and build their pairs explicitly.
 */
public final class CsrfTestPair {

    private CsrfTestPair() {
    }

    /** A request specification with the CSRF cookie and the matching header. */
    public static RequestSpecification withCsrf() {
        String token = RestAssured.given().when().get("/api/v1/csrf-token")
                .then().statusCode(200)
                .extract().jsonPath().getString("token");
        return RestAssured.given()
                .cookie(config("gateway.csrf.cookie-name"), token)
                .header(config("gateway.csrf.header-name"), token);
    }

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }
}
