package fr.demo.souscription.gateway.edge;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.restassured.response.Response;
import java.util.List;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity skeleton for 14-souscription-gateway-stateless-no-session.
 *
 * The source held the whole journey in a 20-minute HttpSession (web.xml session-config,
 * context.xml SameSite=lax). Plan 14 removes it deliberately: the 302 + "votre session a expire"
 * behaviour of SubscriptionIT lines 127-129 has no gateway successor (it belongs to the SPA), but
 * the BOUNDED WINDOW it provided must survive as the CSRF token TTL of plan 15.
 *
 * Here we assert the statelessness itself: no session cookie, client-carried draft id.
 */
@QuarkusTest
class StatelessParityTest {

    private static final List<String> SESSION_NAMES =
            List.of("JSESSIONID", "jsessionid", "SESSION", "sid");

    @Inject
    MeterRegistry registry;

    @Test
    void noSessionCookieOnAnySafeRoute() {
        assertNoSessionCookie(given().when().get("/api/v1/products"));
        assertNoSessionCookie(given().when().get("/api/v1/products/{code}", "PRUDENT"));
        assertNoSessionCookie(given().when()
                .get("/api/v1/drafts/{id}", "00000000-0000-0000-0000-000000000000"));
        assertNoSessionCookie(given().when()
                .get("/api/v1/subscriptions/{key}", "00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void noSessionCookieOnStateChangingRoutes() {
        assertNoSessionCookie(given().contentType("application/json").body("{}")
                .when().post("/api/v1/drafts"));
        assertNoSessionCookie(given().contentType("application/json").body("{}")
                .when().put("/api/v1/drafts/{id}/subscriber",
                        "00000000-0000-0000-0000-000000000000"));
        assertNoSessionCookie(given().contentType("application/json").body("{}")
                .when().post("/api/v1/drafts/{id}/simulation",
                        "00000000-0000-0000-0000-000000000000"));
        assertNoSessionCookie(given().contentType("application/json").body("{}")
                .when().post("/api/v1/subscriptions"));
    }

    @Test
    void malformedDraftIdIsRejectedStructurally() {
        RejectionObservation observation = RejectionObservation.start(registry);
        Response response;
        try {
            response = given().when().get("/api/v1/drafts/{id}", "not-a-uuid");
            observation.assertSingleProblemEntry(400, response.jsonPath().getString("instance"));
        } finally {
            observation.stop();
        }
        assertEquals(400, response.getStatusCode(),
                "the draft id is client-carried and typed UUID, so a malformed value is a"
                        + " structural failure (400), never a 500");
        assertTrue(response.getContentType().startsWith("application/problem+json"));
    }

    private static void assertNoSessionCookie(Response response) {
        for (String name : SESSION_NAMES) {
            assertTrue(response.getCookie(name) == null,
                    "no session cookie may be emitted (CFG-006); found: " + name);
        }
    }
}
