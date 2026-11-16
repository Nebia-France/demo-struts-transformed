package fr.demo.souscription.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parity check 18: one confirmation key still yields exactly one subscription.
 *
 * <p>Source: {@code SubscriptionRepository.save} was idempotent on the unique
 * {@code confirmation_key} and absorbed {@code SQLState 23505}; {@code SubscriptionIT} lines 82-84
 * asserted that a duplicate submission returns the <em>same</em> reference, and line 114 that a
 * stale key yields 409. The call was in-process, so no key ever travelled on a wire.
 *
 * <p>Target: the key is client-supplied and propagated as {@code Idempotency-Key} with no automatic
 * retry. Omitting it creates duplicate subscriptions (CFG-003), which a status-only assertion does
 * not reveal — hence the WireMock <strong>request journal</strong> assertions below.
 *
 * <p>The confirmation write is rooted at {@code POST /api/v1/drafts/{draftId}/confirmation} (see
 * {@code ConfirmationResource}'s class javadoc for why the write lives on {@code DraftResource}),
 * which is the public successor of the legacy confirmation submission.
 */
@QuarkusTest
class IdempotencyParityTest {

    private static final String DRAFT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CONFIRMATION_KEY = "22222222-2222-2222-2222-222222222222";
    private static final String STALE_KEY = "00000000-0000-0000-0000-000000000000";
    private static final String SLOW_DRAFT_ID = "44444444-4444-4444-4444-444444444444";
    private static final String KNOWN_REFERENCE = "33333333-3333-3333-3333-333333333333";

    private static final String DOWNSTREAM_PATH = "/v1/drafts/" + DRAFT_ID + "/confirmation";
    private static final String PROBLEM_JSON = "application/problem+json";

    @BeforeEach
    void resetTheDownstreamJournalAndScenario() {
        WireMockServer subscription = DownstreamWireMocks.server("subscription");
        subscription.resetScenarios();
        subscription.resetRequests();
    }

    @Test
    void duplicateSubmissionCarriesTheSameKeyAndYieldsOneSubscription() {
        Response first = confirm(DRAFT_ID, CONFIRMATION_KEY);
        Response second = confirm(DRAFT_ID, CONFIRMATION_KEY);

        assertEquals(201, first.getStatusCode());
        assertEquals(200, second.getStatusCode(), "a replay must not create a second subscription");
        String firstReference = first.path("reference");
        String secondReference = second.path("reference");
        assertEquals(firstReference, secondReference,
                "a duplicate submission must return the same reference (SubscriptionIT 82-84)");

        WireMockServer subscription = DownstreamWireMocks.server("subscription");
        subscription.verify(2, postRequestedFor(urlPathEqualTo(DOWNSTREAM_PATH))
                .withHeader(SubscriptionClient.IDEMPOTENCY_KEY_HEADER, equalTo(CONFIRMATION_KEY)));
        assertEquals(1, subscription.getServeEvents().getServeEvents().stream()
                        .filter(event -> event.getResponse().getStatus() == 201)
                        .count(),
                "exactly one downstream write must create a subscription");
    }

    @Test
    void theGatewayNeitherGeneratesNorMutatesTheKey() {
        confirm(DRAFT_ID, CONFIRMATION_KEY);

        WireMockServer subscription = DownstreamWireMocks.server("subscription");
        subscription.verify(postRequestedFor(urlPathEqualTo(DOWNSTREAM_PATH))
                .withHeader(SubscriptionClient.IDEMPOTENCY_KEY_HEADER, equalTo(CONFIRMATION_KEY)));

        List<String> keysOnTheWire = subscription.getServeEvents().getServeEvents().stream()
                .map(event -> event.getRequest()
                        .getHeader(SubscriptionClient.IDEMPOTENCY_KEY_HEADER))
                .toList();
        assertEquals(List.of(CONFIRMATION_KEY), keysOnTheWire,
                "the key is client-supplied: a gateway-generated one would change on every retry");
    }

    @Test
    void noRetryIsIssuedOnTheConfirmationWrite() {
        confirm(DRAFT_ID, STALE_KEY).then().statusCode(409);

        DownstreamWireMocks.server("subscription")
                .verify(1, postRequestedFor(urlPathEqualTo(DOWNSTREAM_PATH))
                        .withHeader(SubscriptionClient.IDEMPOTENCY_KEY_HEADER,
                                equalTo(STALE_KEY)));
    }

    @Test
    void downstreamTimeoutYields504WithResubmissionGuidance() {
        Response response = confirm(SLOW_DRAFT_ID, CONFIRMATION_KEY);

        assertEquals(504, response.getStatusCode());
        assertTrue(response.getContentType().startsWith(PROBLEM_JSON),
                "got " + response.getContentType());
        String detail = response.path("detail");
        assertTrue(detail.contains("meme cle de confirmation")
                        && detail.contains(SubscriptionClient.IDEMPOTENCY_KEY_HEADER),
                "the caller must be told to resubmit the SAME key, got: " + detail);
    }

    @Test
    void staleOrInvalidKeyIsRelayedAs409Or400() {
        Response stale = confirm(DRAFT_ID, STALE_KEY);
        assertEquals(409, stale.getStatusCode(),
                "successor of SubscriptionIT line 114 (stale key -> 409)");
        assertTrue(stale.getContentType().startsWith(PROBLEM_JSON));

        Response invalid = confirm(DRAFT_ID, "");
        assertEquals(400, invalid.getStatusCode(), "a structurally invalid key is rejected at the edge");
        assertTrue(invalid.getContentType().startsWith(PROBLEM_JSON));
        DownstreamWireMocks.server("subscription")
                .verify(0, postRequestedFor(urlPathEqualTo(DOWNSTREAM_PATH))
                        .withHeader(SubscriptionClient.IDEMPOTENCY_KEY_HEADER, equalTo("")));
    }

    @Test
    void lookupByKnownReferenceYields200AndByUnknownOneYields404() {
        io.restassured.RestAssured.given()
                .when().get("/api/v1/subscriptions/" + KNOWN_REFERENCE)
                .then().statusCode(200).body("reference", org.hamcrest.Matchers.notNullValue());

        io.restassured.RestAssured.given()
                .when().get("/api/v1/subscriptions/" + UUID.randomUUID())
                .then().statusCode(404).contentType(PROBLEM_JSON);
    }

    private static Response confirm(String draftId, String key) {
        return fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf()
                .contentType(ContentType.JSON)
                .body(Map.of("confirmationKey", key))
                .when().post("/api/v1/drafts/" + draftId + "/confirmation");
    }
}
