package fr.demo.souscription.gateway.edge;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import fr.demo.souscription.gateway.observability.RejectionMetrics;
import fr.demo.souscription.gateway.runtime.GatewayConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Successors of the CSRF assertions of the legacy {@code SubscriptionIT} (W7-T02, CFG-013).
 *
 * <p>The legacy test parsed the rendered page to extract the {@code csrf} form field and posted it
 * back with the session cookie. The gateway serves JSON only: the token is acquired from the
 * {@code XSRF-TOKEN} cookie set on a safe request and replayed in the {@code X-CSRF-Token} header.
 * No HTML is parsed. The three downstreams are the contract-derived WireMock doubles, so no sibling
 * service is needed.
 *
 * <p>Every negative case also asserts the {@code gateway.rejections} series it must move (plan 26)
 * and the single structured log entry it must write (plan 27).
 *
 * <p>Mapping to {@code SubscriptionIT} lines is listed in {@code docs/assertion-successors.md}.
 */
@QuarkusTest
class CsrfEdgeTest {

    /** Draft known to the subscription double. */
    private static final String DRAFT_ID = "11111111-1111-1111-1111-111111111111";

    private static final Map<String, Object> SUBSCRIBER = Map.of(
            "firstName", "Camille", "lastName", "Martin",
            "email", "camille@example.test", "birthDate", "1980-01-01");

    @Inject
    GatewayConfig config;

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

    /** SubscriptionIT l.122: POST without any token is 403. */
    @Test
    void missingTokenIsRejected() {
        double before = observation.count(403, RejectionMetrics.CSRF_MISSING);
        String instance = given().contentType(ContentType.JSON).body("{}")
                .when().put("/api/v1/drafts/{id}/subscriber", DRAFT_ID)
                .then().statusCode(403).contentType("application/problem+json")
                .body("status", equalTo(403))
                .extract().jsonPath().getString("instance");
        observation.assertSingleRejection(403, RejectionMetrics.CSRF_MISSING, before, instance);
    }

    /** SubscriptionIT l.123: a forged token (the cookie holds the real one) is 403. */
    @Test
    void forgedTokenIsRejected() {
        String token = cookieToken();
        double before = observation.count(403, RejectionMetrics.CSRF_MISMATCH);
        String instance = withPair(token, "forged").body(Map.of("productCode", "PRUDENT"))
                .when().post("/api/v1/drafts")
                .then().statusCode(403).contentType("application/problem+json")
                .extract().jsonPath().getString("instance");
        observation.assertSingleRejection(403, RejectionMetrics.CSRF_MISMATCH, before, instance);
    }

    /** SubscriptionIT l.123: a forged pair, equal in cookie and header but never issued, is 403. */
    @Test
    void forgedMatchingPairIsRejected() {
        double before = observation.count(403, RejectionMetrics.CSRF_MISMATCH);
        String instance = withPair("forged", "forged").body(Map.of("productCode", "PRUDENT"))
                .when().post("/api/v1/drafts")
                .then().statusCode(403).contentType("application/problem+json")
                .extract().jsonPath().getString("instance");
        observation.assertSingleRejection(403, RejectionMetrics.CSRF_MISMATCH, before, instance);
    }

    /**
     * SubscriptionIT l.127-130: once the former 20-minute session had expired, a confirmation
     * carrying the previously valid token was 403. The expiry is now encoded in the signed token
     * itself (plan 15): a correctly signed token older than {@code gateway.csrf.ttl} is 403.
     */
    @Test
    void expiredTokenIsRejected() {
        String expired = signedToken(Instant.now().minus(config.csrf().ttl()).minusSeconds(60));
        double before = observation.count(403, RejectionMetrics.CSRF_MISMATCH);
        String instance = withPair(expired, expired)
                .body(Map.of("confirmationKey", "22222222-2222-2222-2222-222222222222"))
                .when().post("/api/v1/drafts/{id}/confirmation", DRAFT_ID)
                .then().statusCode(403).contentType("application/problem+json")
                .extract().jsonPath().getString("instance");
        observation.assertSingleRejection(403, RejectionMetrics.CSRF_MISMATCH, before, instance);
    }

    /**
     * SubscriptionIT l.127-129 (the "session expired" path): a safe request carrying an expired
     * token is answered normally and receives a fresh cookie, which the client replays.
     */
    @Test
    void expiredTokenIsReplacedOnTheNextSafeRequest() {
        String expired = signedToken(Instant.now().minus(config.csrf().ttl()).minusSeconds(60));
        String fresh = given().cookie(config.csrf().cookieName(), expired)
                .when().get("/api/v1/products")
                .then().statusCode(200).extract().cookie(config.csrf().cookieName());
        assertNotNull(fresh, "an expired token must be replaced by a fresh cookie");
        assertNotEquals(expired, fresh);
        withPair(fresh, fresh).body(SUBSCRIBER)
                .when().put("/api/v1/drafts/{id}/subscriber", DRAFT_ID)
                .then().statusCode(200);
        observation.assertNoRejection();
    }

    /** SubscriptionIT l.50-53 / l.135: the token taken from the cookie, echoed in the header, passes. */
    @Test
    void cookieTokenEchoedInHeaderIsAccepted() {
        String token = cookieToken();
        withPair(token, token).body(SUBSCRIBER)
                .when().put("/api/v1/drafts/{id}/subscriber", DRAFT_ID)
                .then().statusCode(200);
        observation.assertNoRejection();
    }

    /** SubscriptionIT l.124: a valid token with a structurally invalid code is 400, not 403. */
    @Test
    void validTokenWithInvalidProductCodeIs400() {
        String token = cookieToken();
        String instance = withPair(token, token).body(Map.of("productCode", "bad"))
                .when().post("/api/v1/drafts")
                .then().statusCode(400).contentType("application/problem+json")
                .extract().jsonPath().getString("instance");
        observation.assertSingleProblemEntry(400, instance);
    }

    /** Token acquisition: the {@code XSRF-TOKEN} cookie of a safe request, never a form field. */
    private String cookieToken() {
        String token = given().when().get("/api/v1/products")
                .then().statusCode(200).extract().cookie(config.csrf().cookieName());
        assertNotNull(token, "GET /api/v1/products must set the " + config.csrf().cookieName() + " cookie");
        return token;
    }

    private RequestSpecification withPair(String cookie, String header) {
        return given().contentType(ContentType.JSON)
                .cookie(config.csrf().cookieName(), cookie)
                .header(config.csrf().headerName(), header);
    }

    /** A token in the wire format of {@code CsrfTokenService}, correctly signed, issued at {@code at}. */
    private String signedToken(Instant at) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String payload = encoder.encodeToString(raw) + "." + at.getEpochSecond();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.csrf().signingKey().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return payload + "." + encoder.encodeToString(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
