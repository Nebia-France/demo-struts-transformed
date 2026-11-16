package fr.demo.souscription.gateway.edge;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * W3-T01 / CFG-006: the gateway emits no session cookie on any route of
 * {@code docs/routing-matrix.md}. The legacy application created a 20-minute {@code JSESSIONID}
 * on the first request of the journey; the stateless gateway carries the draft identifier in the
 * URI instead, so horizontal scaling needs no session affinity (NFG-012).
 *
 * <p>After plan 16 the only cookie the gateway may emit is the CSRF double-submit cookie; any
 * other {@code Set-Cookie} name matching a session-like pattern fails this test.
 */
@QuarkusTest
class NoSessionCookieTest {

    private static final List<String> SESSION_COOKIE_NAMES =
            List.of("jsessionid", "quarkus-session", "session", "sessionid", "sid", "sess");

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
    void noRouteEmitsASessionCookie() {
        String draftId = UUID.randomUUID().toString();
        String reference = UUID.randomUUID().toString();

        assertNoSessionCookie(given().when().get("/api/v1/products"));
        assertNoSessionCookie(given().when().get("/api/v1/products/PRUDENT"));
        assertNoSessionCookie(given().contentType(ContentType.JSON)
                .body(Map.of("productCode", "PRUDENT", "initialAmount", "1000.00",
                        "monthlyAmount", "100.00", "durationYears", 10))
                .when().post("/api/v1/simulations"));
        assertNoSessionCookie(given().contentType(ContentType.JSON)
                .body(Map.of("productCode", "PRUDENT"))
                .when().post("/api/v1/drafts"));
        assertNoSessionCookie(given().when().get("/api/v1/drafts/" + draftId));
        assertNoSessionCookie(given().contentType(ContentType.JSON)
                .body(Map.of("firstName", "Alice", "lastName", "Durand",
                        "email", "alice@example.com", "birthDate", "1990-01-15"))
                .when().put("/api/v1/drafts/" + draftId + "/subscriber"));
        assertNoSessionCookie(given().contentType(ContentType.JSON)
                .body(Map.of("initialAmount", "1000.00", "monthlyAmount", "100.00",
                        "durationYears", 10))
                .when().put("/api/v1/drafts/" + draftId + "/simulation"));
        assertNoSessionCookie(given().contentType(ContentType.JSON)
                .body(Map.of("confirmationKey", UUID.randomUUID().toString()))
                .when().post("/api/v1/drafts/" + draftId + "/confirmation"));
        assertNoSessionCookie(given().when().get("/api/v1/subscriptions/" + reference));
    }

    @Test
    void aClientSuppliedSessionCookieIsNeverEchoedBack() {
        assertNoSessionCookie(given().cookie("JSESSIONID", "A1B2C3")
                .when().get("/api/v1/products"));
    }

    @Test
    void aMalformedClientSuppliedDraftIdIsRejectedAs400() {
        String instance = given().when().get("/api/v1/drafts/not-a-uuid")
                .then().statusCode(400)
                .contentType("application/problem+json")
                .extract().jsonPath().getString("instance");
        observation.assertSingleProblemEntry(400, instance);
    }

    private void assertNoSessionCookie(Response response) {
        for (String header : response.getHeaders().getValues("Set-Cookie")) {
            String name = header.split("=", 2)[0].trim().toLowerCase(Locale.ROOT);
            assertTrue(!SESSION_COOKIE_NAMES.contains(name),
                    "Session cookie emitted: " + header);
        }
    }
}
