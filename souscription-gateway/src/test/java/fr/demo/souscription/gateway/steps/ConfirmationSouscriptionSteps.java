package fr.demo.souscription.gateway.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import fr.demo.souscription.gateway.client.SubscriptionClient;
import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Et;
import io.cucumber.java.fr.Quand;
import io.cucumber.java.fr.Soit;
import io.restassured.response.Response;
import java.util.List;

/**
 * Gateway glue for the two confirmation scenarios owned by souscription-gateway (W7-T01):
 * « Double soumission de la confirmation avec la meme cle » (idempotency key propagated, no retry,
 * W4-T02) and « Indisponibilite de l'enregistrement » (503 with {@code Retry-After}, W4-T05).
 *
 * <p>The other scenarios of {@code features/confirmation-souscription} are owned by
 * {@code souscription-service} or by the {@code end-to-end} suite and are not run here (see
 * {@code features/SCENARIO_OWNERSHIP.md}). The glue written for them in earlier waves is kept but
 * not selected by the run.
 *
 * <p>Reinterpretations of the source behaviour, all recorded in {@code docs/stateless-model.md} and
 * {@code docs/routing-matrix.md}:
 * <ul>
 *   <li>the HTML redirect to {@code /confirmation.do} becomes the JSON representation of the
 *       created subscription, located by {@code Location: /api/v1/subscriptions/{reference}};</li>
 *   <li>« le brouillon est retire de la session » becomes the absence of any server-side session:
 *       the draft lives in souscription-service and is addressed by its identifier;</li>
 *   <li>the source looked the subscription up by its confirmation key; the published API exposes it
 *       by the reference returned at confirmation time, the key staying a write-side concern.</li>
 * </ul>
 *
 * <p>The « une seule souscription est enregistree » verdict is read from the downstream request
 * journal: the single write is the observable proof that the {@code Idempotency-Key} travelled.
 */
public class ConfirmationSouscriptionSteps extends GatewayStepsSupport {

    private static final String SUBSCRIPTION_DOWNSTREAM = "subscription";

    /** Key no longer carried by the draft; the downstream answers 409 for it. */
    private static final String STALE_KEY = "00000000-0000-0000-0000-000000000000";

    private String draftId;
    private String confirmationKey;
    private String reference;

    /** Trace id sent with the last confirmation, as an unsampled caller decision. */
    private String confirmationTraceId;

    @Soit("l'utilisateur a enregistre une simulation pour le produit {string}")
    public void aSimulationHasBeenAttachedToTheDraft(String productCode) {
        GatewayUnderTest.ensureStarted(testPort());
        WireMockServer subscription = DownstreamWireMocks.server(SUBSCRIPTION_DOWNSTREAM);
        // Restores the contract mappings: a previous scenario may have overridden the confirmation
        // edge to simulate a downstream outage.
        subscription.resetToDefaultMappings();
        subscription.resetScenarios();
        subscription.resetRequests();

        Response started = fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf()
                .contentType("application/json")
                .body("{\"productCode\":\"" + productCode + "\"}")
                .post("/api/v1/drafts");
        assertEquals(201, started.statusCode(), "le brouillon doit etre cree");
        this.draftId = started.jsonPath().getString("draftId");
        this.confirmationKey = started.jsonPath().getString("confirmationKey");
        assertNotNull(confirmationKey, "le brouillon doit porter une cle de confirmation");
        recordResponse(started);
    }

    /**
     * The acceptance checkbox is a presentation concern of {@code souscription-web}: the published
     * confirmation contract carries the key only. Nothing is asserted at the gateway.
     */
    @Soit("l'utilisateur a accepte les informations et les hypotheses de simulation")
    public void theUserAcceptedTheSimulationAssumptions() {
        assertNotNull(draftId, "le brouillon doit exister avant l'acceptation");
    }

    @Soit("une souscription existe deja pour la cle de confirmation du brouillon")
    public void aSubscriptionAlreadyExistsForTheKey() {
        confirmWithTheDraftKey();
        assertEquals(201, lastResponse().statusCode());
        this.reference = lastResponse().jsonPath().getString("reference");
    }

    @Soit("la souscription de l'utilisateur a ete enregistree")
    public void theSubscriptionHasBeenRecorded() {
        aSubscriptionAlreadyExistsForTheKey();
    }

    @Quand("l'utilisateur confirme la demande avec la cle de confirmation du brouillon")
    public void confirmWithTheDraftKey() {
        recordResponse(confirm(confirmationKey));
        if (lastResponse().statusCode() < 400) {
            this.reference = lastResponse().jsonPath().getString("reference");
        }
    }

    @Quand("l'utilisateur soumet a nouveau la confirmation avec la meme cle")
    public void resubmitTheSameKey() {
        confirmWithTheDraftKey();
    }

    @Quand("le systeme enregistre la souscription pour cette cle")
    public void theSystemRecordsTheSubscriptionForThatKey() {
        confirmWithTheDraftKey();
    }

    @Quand("l'utilisateur confirme la demande avec une cle de confirmation erronee")
    public void confirmWithAWrongKey() {
        recordResponse(confirm(STALE_KEY));
    }

    @Quand("le systeme recherche la souscription avec la cle de confirmation utilisee")
    public void readBackTheRecordedSubscription() {
        assertNotNull(reference, "aucune souscription enregistree a relire");
        recordResponse(request().get("/api/v1/subscriptions/" + reference));
    }

    @Quand("le systeme recherche une souscription avec la cle {string}")
    public void searchForAnUnknownKey(String key) {
        recordResponse(request().get("/api/v1/subscriptions/" + key));
    }

    @Alors("la souscription est enregistree avec une reference unique")
    public void theSubscriptionIsRecordedWithAUniqueReference() {
        assertEquals(201, lastResponse().statusCode());
        assertNotNull(lastResponse().jsonPath().getString("reference"));
        assertNotNull(lastResponse().header("Location"), "la ressource creee doit etre localisee");
    }

    /**
     * The source redirected to an HTML page; the API answers with the subscription itself, located
     * by {@code Location} on the first confirmation and returned as-is on a replay.
     */
    @Et("le systeme redirige l'utilisateur vers {string}")
    public void theSubscriptionResourceIsNamedInsteadOfARedirect(String legacyPath) {
        assertTrue(lastResponse().statusCode() == 201 || lastResponse().statusCode() == 200,
                "statut inattendu: " + lastResponse().statusCode() + " pour " + legacyPath);
        String confirmed = lastResponse().jsonPath().getString("reference");
        assertNotNull(confirmed, "la reponse doit porter la reference que " + legacyPath
                + " affichait");
        if (lastResponse().statusCode() == 201) {
            assertTrue(lastResponse().header("Location").endsWith(confirmed),
                    "Location doit designer /api/v1/subscriptions/{reference}");
        }
    }

    @Et("le brouillon est retire de la session")
    public void noSessionHoldsTheDraft() {
        assertNull(lastResponse().getCookie("JSESSIONID"),
                "la passerelle est sans etat: le brouillon appartient a souscription-service");
    }

    @Et("une seule souscription est enregistree")
    public void exactlyOneSubscriptionIsRecorded() {
        assertEquals(reference, lastResponse().jsonPath().getString("reference"),
                "la seconde soumission doit retourner la meme reference");
        assertEquals(1, downstreamCreations(), "une seule ecriture en aval est autorisee");
        assertTrue(downstreamConfirmationsCarryingTheKey() >= 2,
                "les deux soumissions doivent porter l'en-tete "
                        + SubscriptionClient.IDEMPOTENCY_KEY_HEADER);
    }

    @Alors("la souscription existante est retournee sans nouvel enregistrement")
    public void theExistingSubscriptionIsReturned() {
        assertEquals(200, lastResponse().statusCode());
        assertEquals(reference, lastResponse().jsonPath().getString("reference"));
        assertEquals(1, downstreamCreations(), "aucun second enregistrement n'est autorise");
    }

    @Et("aucune souscription n'est enregistree")
    public void noSubscriptionIsRecorded() {
        assertEquals(0, downstreamCreations());
    }

    @Alors("aucune souscription n'est retournee")
    public void noSubscriptionIsReturned() {
        assertEquals(404, lastResponse().statusCode());
        assertNull(lastResponse().jsonPath().getString("reference"));
    }

    @Alors("la souscription retournee porte les donnees du souscripteur et de la simulation")
    public void theSubscriptionCarriesTheSubscriberAndTheSimulation() {
        assertEquals(200, lastResponse().statusCode());
        assertNotNull(lastResponse().jsonPath().getString("subscriber.lastName"));
        assertNotNull(lastResponse().jsonPath().getString("subscriber.email"));
        assertNotNull(lastResponse().jsonPath().getString("simulation.finalCapital"));
    }

    /**
     * Gateway side of « Indisponibilite de l'enregistrement » (W4-T05): the write edge is made
     * unavailable, which the shared downstream mapper turns into the public 503.
     */
    @Soit("l'enregistrement des souscriptions est momentanement indisponible")
    public void theSubscriptionStoreIsUnavailable() {
        DownstreamWireMocks.server(SUBSCRIPTION_DOWNSTREAM).stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .post(com.github.tomakehurst.wiremock.client.WireMock
                                .urlPathMatching("/v1/drafts/[^/]+/confirmation"))
                        .atPriority(1)
                        .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                                .withStatus(503)
                                .withHeader("Content-Type", "application/problem+json")
                                .withBody("{\"type\":\"about:blank\",\"title\":\"Service "
                                        + "Unavailable\",\"status\":503,\"detail\":\"stockage "
                                        + "indisponible cote aval\"}")));
    }

    /**
     * The source re-rendered the summary page with a field-level error; the gateway renders no view.
     * Its equivalent guarantee is a normalised {@code problem+json} refusal that advertises the
     * failure as transient ({@code Retry-After}) and discloses nothing of the downstream body —
     * souscription-web is what turns that into the « {@code storage} » message of the summary.
     */
    @Et("le systeme reaffiche le recapitulatif avec l'erreur sur le champ {string}")
    public void theRefusalIsANormalisedProblem(String field) {
        assertEquals(503, lastResponse().statusCode(), lastResponse().asString());
        assertTrue(String.valueOf(lastResponse().contentType()).contains("problem+json"),
                "le refus doit etre en problem+json : " + lastResponse().contentType());
        assertNotNull(lastResponse().header("Retry-After"),
                "l'indisponibilite doit etre annoncee comme transitoire pour le champ " + field);
        assertFalse(lastResponse().asString().contains("indisponible cote aval"),
                "aucun corps aval ne doit fuiter : " + lastResponse().asString());
        assertTheFailureIsTraced();
    }

    /**
     * The refusal must also be diagnosable (W6-T04): although the caller's decision is "unsampled"
     * and the uniform ratio is 10 %, the error-always rule exports the complete gateway trace —
     * the server span answering 503, the confirmation journey span and the failing client span of
     * the souscription-service call — under the caller's trace id.
     */
    private void assertTheFailureIsTraced() {
        long deadline = System.nanoTime() + 10_000_000_000L;
        List<OtlpCollectorStub.ExportedSpan> trace = List.of();
        while (System.nanoTime() < deadline) {
            trace = OtlpCollectorStub.spans().stream()
                    .filter(span -> span.traceId().equals(confirmationTraceId)).toList();
            if (isCompleteFailureTrace(trace)) {
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("trace d'echec incomplete pour " + confirmationTraceId + " : " + trace);
    }

    private static boolean isCompleteFailureTrace(List<OtlpCollectorStub.ExportedSpan> trace) {
        boolean server = trace.stream().anyMatch(span -> span.kind() == OtlpCollectorStub.KIND_SERVER
                && span.statusCode() == OtlpCollectorStub.STATUS_ERROR);
        boolean journey = trace.stream().anyMatch(span -> span.name().equals("confirm-journey"));
        boolean failingCall = trace.stream().anyMatch(span -> span.kind() == OtlpCollectorStub.KIND_CLIENT
                && span.statusCode() == OtlpCollectorStub.STATUS_ERROR);
        return server && journey && failingCall;
    }

    /**
     * « la saisie de l'utilisateur est conservee »: the gateway holds no session, so what must
     * survive the failed confirmation is the server-side draft, still readable by its identifier.
     */
    @Et("la saisie de l'utilisateur est conservee")
    public void theDraftSurvivesTheFailedConfirmation() {
        Response draft = request().get("/api/v1/drafts/" + draftId);
        assertEquals(200, draft.statusCode(), draft.asString());
        assertNotNull(draft.jsonPath().getString("draftId"),
                "le brouillon doit rester adressable apres l'echec");
    }

    private Response confirm(String key) {
        confirmationTraceId = randomHex(16);
        return fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf()
                .header("traceparent", "00-" + confirmationTraceId + "-" + randomHex(8) + "-00")
                .contentType("application/json")
                .body("{\"confirmationKey\":\"" + key + "\"}")
                .post("/api/v1/drafts/" + draftId + "/confirmation");
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        new java.security.SecureRandom().nextBytes(value);
        return java.util.HexFormat.of().formatHex(value);
    }

    /** Downstream confirmations that actually created a subscription (201). */
    private long downstreamCreations() {
        return confirmationEvents()
                .filter(event -> event.getResponse().getStatus() == 201)
                .count();
    }

    private long downstreamConfirmationsCarryingTheKey() {
        return confirmationEvents()
                .filter(event -> confirmationKey.equals(event.getRequest()
                        .getHeader(SubscriptionClient.IDEMPOTENCY_KEY_HEADER)))
                .count();
    }

    private java.util.stream.Stream<ServeEvent> confirmationEvents() {
        return DownstreamWireMocks.server(SUBSCRIPTION_DOWNSTREAM).getServeEvents()
                .getServeEvents().stream()
                .filter(event -> event.getRequest().getUrl().endsWith("/confirmation"));
    }
}
