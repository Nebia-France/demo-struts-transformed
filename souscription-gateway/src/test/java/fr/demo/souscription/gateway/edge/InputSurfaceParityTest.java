package fr.demo.souscription.gateway.edge;

import fr.demo.souscription.gateway.observability.RejectionMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * Parity skeleton for 10-souscription-gateway-filter-skeleton-input-limits.
 *
 * The source accepted only Struts form parameters, so a JSON body is a NEW attack surface and
 * these bounds are a hardening rather than a preserved behaviour. The one genuinely preserved
 * assertion is the 400 on a structurally invalid product code: SubscriptionIT line 124 asserted
 * 400 for POST /commencer.do with code=BAD and a valid CSRF token.
 */
@QuarkusTest
class InputSurfaceParityTest {

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

    @Test
    void bodyAboveTheConfiguredLimitIsRejected() {
        double before = observation.count(413, RejectionMetrics.SIZE);
        String instance = CsrfTestPair.withCsrf().contentType("application/json")
                .body(oversizedJsonBody())
                .when().post("/api/v1/drafts")
                .then().statusCode(413)
                .extract().jsonPath().getString("instance");
        observation.assertSingleRejection(413, RejectionMetrics.SIZE, before, instance);
    }

    @Test
    void structurallyInvalidProductCodeIsRejectedWith400() {
        String instance = CsrfTestPair.withCsrf().contentType("application/json")
                .body("{\"code\":\"\"}")
                .when().post("/api/v1/drafts")
                .then().statusCode(400).contentType("application/problem+json")
                .extract().jsonPath().getString("instance");
        observation.assertSingleProblemEntry(400, instance);
    }

    @Test
    void unexpectedContentTypeIsRejectedWith415() {
        String instance = CsrfTestPair.withCsrf().contentType("text/plain")
                .body("code=PRUDENT")
                .when().post("/api/v1/drafts")
                .then().statusCode(415)
                .extract().jsonPath().getString("instance");
        observation.assertSingleProblemEntry(415, instance);
    }

    @Test
    void oversizedHeaderIsRejected() {
        given().header("X-Probe", "a".repeat(16 * 1024))
                .when().get("/api/v1/products")
                .then().statusCode(org.hamcrest.Matchers.not(200));
        // Refused by the HTTP codec before any route filter runs: outside the closed set of
        // gateway.rejections, so nothing may be counted or logged as an edge rejection.
        observation.assertNoRejection();
    }

    /** 65 KB of JSON: one kilobyte above the configured 64K bound. */
    private static String oversizedJsonBody() {
        return "{\"code\":\"" + "A".repeat(65 * 1024) + "\"}";
    }
}
