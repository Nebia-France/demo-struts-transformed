package fr.demo.souscription.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Propagation of the confirmation key on the wire (W4-T02).
 *
 * <p>The source enforced idempotency in-process: {@code SubscriptionRepository.save} returned the
 * existing row for a known {@code confirmation_key} and absorbed the {@code 23505} unique-constraint
 * violation. The call is now remote, so that guarantee only survives if the key travels with the
 * request — the classic duplicate-write bug of this migration (CFG-003).
 *
 * <p>What is asserted here is the wire itself, through the WireMock request journal: both
 * submissions carry the same {@code Idempotency-Key}, exactly one of them creates a subscription,
 * and the two answers name the same reference. The gateway declares no automatic retry on this
 * call, so a timeout surfaces as 504 telling the caller to re-submit the same key.
 */
@QuarkusTest
class IdempotencyTest {

    private static final String DRAFT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CONFIRMATION_KEY = "22222222-2222-2222-2222-222222222222";
    private static final String STALE_KEY = "00000000-0000-0000-0000-000000000000";
    private static final String SLOW_DRAFT_ID = "44444444-4444-4444-4444-444444444444";

    private static final String DOWNSTREAM_PATH = "/v1/drafts/" + DRAFT_ID + "/confirmation";

    private static final String PROBLEM_JSON = "application/problem+json";

    @BeforeEach
    void resetTheDownstreamJournalAndScenario() {
        WireMockServer subscription = DownstreamWireMocks.server("subscription");
        subscription.resetScenarios();
        subscription.resetRequests();
    }

    @Test
    void twoSubmissionsOfTheSameKeyCarryItDownstreamAndWriteOnce() {
        String reference = confirm(DRAFT_ID, CONFIRMATION_KEY)
                .then().statusCode(201)
                .extract().path("reference");

        confirm(DRAFT_ID, CONFIRMATION_KEY)
                .then().statusCode(200)
                .body("reference", is(reference));

        WireMockServer subscription = DownstreamWireMocks.server("subscription");
        subscription.verify(2, postRequestedFor(urlPathEqualTo(DOWNSTREAM_PATH))
                .withHeader(SubscriptionClient.IDEMPOTENCY_KEY_HEADER, equalTo(CONFIRMATION_KEY)));
        assertEquals(1, subscription.getServeEvents().getServeEvents().stream()
                        .filter(event -> event.getResponse().getStatus() == 201)
                        .count(),
                "a single subscription must be created for a replayed confirmation key");
    }

    @Test
    void aKeyTheDraftNoLongerCarriesIsRejectedAsAConflict() {
        confirm(DRAFT_ID, STALE_KEY)
                .then().statusCode(409)
                .contentType(PROBLEM_JSON)
                .body("status", is(409));

        DownstreamWireMocks.server("subscription")
                .verify(0, postRequestedFor(urlPathEqualTo(DOWNSTREAM_PATH))
                        .withHeader(SubscriptionClient.IDEMPOTENCY_KEY_HEADER,
                                equalTo(CONFIRMATION_KEY)));
    }

    @Test
    void aStructurallyInvalidKeyIsRejectedBeforeAnyDownstreamCall() {
        confirm(DRAFT_ID, "")
                .then().statusCode(400)
                .contentType(PROBLEM_JSON);

        DownstreamWireMocks.server("subscription")
                .verify(0, postRequestedFor(urlPathEqualTo(DOWNSTREAM_PATH)));
    }

    @Test
    void aDownstreamTimeoutAnswers504AndAsksForTheSameKeyAgain() {
        confirm(SLOW_DRAFT_ID, CONFIRMATION_KEY)
                .then().statusCode(504)
                .contentType(PROBLEM_JSON)
                .body("status", is(504))
                .body("detail", containsString("meme cle de confirmation"))
                .body("detail", containsString(SubscriptionClient.IDEMPOTENCY_KEY_HEADER));
    }

    private static io.restassured.response.Response confirm(String draftId, String key) {
        return csrf().contentType(ContentType.JSON)
                .body(Map.of("confirmationKey", key))
                .when().post("/api/v1/drafts/" + draftId + "/confirmation");
    }

    private static io.restassured.specification.RequestSpecification csrf() {
        return fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf();
    }
}
