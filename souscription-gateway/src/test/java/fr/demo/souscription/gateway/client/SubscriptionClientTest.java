package fr.demo.souscription.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import fr.demo.souscription.gateway.api.dto.DraftStartRequest;
import fr.demo.souscription.gateway.api.dto.DraftView;
import fr.demo.souscription.gateway.api.dto.SimulationInput;
import fr.demo.souscription.gateway.api.dto.SubscriberInput;
import fr.demo.souscription.gateway.api.dto.SubscriptionConfirmation;
import io.quarkus.test.junit.QuarkusTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

/**
 * Path, status and DTO shape of {@link SubscriptionClient} against the WireMock double of
 * {@code souscription-gateway-to-souscription-service} (W4-T01). The confirmation itself is
 * asserted end to end by {@code GatewayApiRoutesTest} because its 201-then-200 semantics consume
 * the stub's scenario state.
 */
@QuarkusTest
class SubscriptionClientTest {

    private static final UUID DRAFT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String REFERENCE = "33333333-3333-3333-3333-333333333333";

    @RestClient
    SubscriptionClient subscriptions;

    @Test
    void startDraftPostsTheContractPathAndDeserialisesTheDraft() {
        DraftView draft = subscriptions.startDraft(new DraftStartRequest("PRUDENT"))
                .await().atMost(Duration.ofSeconds(5));

        assertEquals(DRAFT_ID.toString(), draft.draftId());
        assertEquals("STARTED", draft.status());
        assertEquals("PRUDENT", draft.product().code());
        assertNotNull(draft.confirmationKey());
        DownstreamWireMocks.server("subscription")
                .verify(postRequestedFor(urlPathEqualTo("/v1/drafts")));
    }

    @Test
    void getDraftReadsTheContractPath() {
        assertEquals("STARTED",
                subscriptions.getDraft(DRAFT_ID).await().atMost(Duration.ofSeconds(5)).status());
    }

    @Test
    void anUnknownDraftFailsWithDownstreamNotFound() {
        assertThrows(DownstreamNotFoundException.class,
                () -> subscriptions.getDraft(UUID.randomUUID()).await().atMost(Duration.ofSeconds(5)));
    }

    @Test
    void putSubscriberPutsTheContractPath() {
        DraftView draft = subscriptions.putSubscriber(DRAFT_ID, new SubscriberInput(
                        "Alice", "Durand", "alice@example.com", LocalDate.of(1990, 1, 15)))
                .await().atMost(Duration.ofSeconds(5));

        assertEquals("SUBSCRIBER_CAPTURED", draft.status());
        assertEquals("Durand", draft.subscriber().lastName());
        DownstreamWireMocks.server("subscription").verify(putRequestedFor(
                urlPathEqualTo("/v1/drafts/" + DRAFT_ID + "/subscriber")));
    }

    @Test
    void putSimulationPutsTheContractPathAndKeepsTheServerComputedResult() {
        DraftView draft = subscriptions.putSimulation(DRAFT_ID, new SimulationInput(
                        new BigDecimal("1000.00"), new BigDecimal("100.00"), 10))
                .await().atMost(Duration.ofSeconds(5));

        assertEquals("SIMULATED", draft.status());
        assertEquals(0, new BigDecimal("14963.21").compareTo(draft.simulation().finalCapital()));
        DownstreamWireMocks.server("subscription").verify(putRequestedFor(
                urlPathEqualTo("/v1/drafts/" + DRAFT_ID + "/simulation")));
    }

    @Test
    void getSubscriptionReadsTheContractPath() {
        SubscriptionConfirmation confirmation = subscriptions.getSubscription(REFERENCE)
                .await().atMost(Duration.ofSeconds(5));

        assertEquals(REFERENCE, confirmation.reference());
        assertEquals("PRUDENT", confirmation.product().code());
        assertNotNull(confirmation.createdAt());
    }

    @Test
    void anUnknownReferenceFailsWithDownstreamNotFound() {
        assertThrows(DownstreamNotFoundException.class, () -> subscriptions
                .getSubscription(UUID.randomUUID().toString())
                .await().atMost(Duration.ofSeconds(5)));
    }
}
