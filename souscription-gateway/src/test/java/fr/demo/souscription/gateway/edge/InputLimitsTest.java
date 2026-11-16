package fr.demo.souscription.gateway.edge;

import static io.restassured.RestAssured.given;

import fr.demo.souscription.gateway.observability.RejectionMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Input-surface hardening checks for W1-T05: body size limit, structural validation and media-type
 * rejection. See {@code docs/edge-filters.md}.
 */
@QuarkusTest
class InputLimitsTest {

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
    void bodyAboveTheLimitAnswers413() {
        String oversized = "{\"productCode\":\"" + "A".repeat(65 * 1024) + "\"}";
        double before = observation.count(413, RejectionMetrics.SIZE);
        String instance = CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .body(oversized)
                .when().post("/api/v1/drafts")
                .then().statusCode(413)
                .extract().jsonPath().getString("instance");
        observation.assertSingleRejection(413, RejectionMetrics.SIZE, before, instance);
    }

    @Test
    void blankProductCodeAnswers400() {
        String instance = CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .body(Map.of("productCode", ""))
                .when().post("/api/v1/drafts")
                .then().statusCode(400)
                .extract().jsonPath().getString("instance");
        observation.assertSingleProblemEntry(400, instance);
    }

    @Test
    void unexpectedContentTypeAnswers415() {
        String instance = CsrfTestPair.withCsrf().contentType(ContentType.TEXT)
                .body("productCode=PRUDENT")
                .when().post("/api/v1/drafts")
                .then().statusCode(415)
                .extract().jsonPath().getString("instance");
        observation.assertSingleProblemEntry(415, instance);
    }
}
