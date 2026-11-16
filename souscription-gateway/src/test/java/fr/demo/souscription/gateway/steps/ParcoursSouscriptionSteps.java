package fr.demo.souscription.gateway.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.tomakehurst.wiremock.WireMockServer;
import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import fr.demo.souscription.gateway.edge.CsrfTestPair;
import io.cucumber.java.fr.Quand;
import io.cucumber.java.fr.Soit;
import io.restassured.response.Response;

/**
 * Gateway glue for the three gateway-owned scenarios of
 * {@code features/parcours-souscription/parcours-souscription.feature} (W7-T01):
 * « Methode HTTP non conforme a l'etape du parcours » (its steps live in {@link SecuriteWebSteps},
 * which translates the legacy {@code *.do} paths), « Chemin inconnu dans le parcours » and
 * « Commande inconnue a l'etape de simulation ».
 *
 * <p>The other scenarios of the feature are owned by {@code souscription-web},
 * {@code souscription-service} or the {@code end-to-end} suite (see
 * {@code features/SCENARIO_OWNERSHIP.md}).
 *
 * <p>Reinterpretations for the stateless REST gateway:
 * <ul>
 *   <li>« une session valide » is a valid CSRF double-submit pair: the gateway holds no session;</li>
 *   <li>the legacy {@code command} dispatch parameter has no representation in the public contract.
 *       A simulation submission carrying it, whatever its value, has an undeclared member and is
 *       rejected 400 at the edge ({@code quarkus.jackson.fail-on-unknown-properties=true}) before
 *       any call to souscription-service.</li>
 * </ul>
 */
public class ParcoursSouscriptionSteps extends GatewayStepsSupport {

    private static final String SUBSCRIPTION_DOWNSTREAM = "subscription";

    /** Draft id of the contract-derived double ({@code wiremock/subscription/mappings}). */
    private static final String UNKNOWN_STEP_PATH =
            "/api/v1/drafts/11111111-1111-1111-1111-111111111111/etape-inconnue";

    private static final String SUBSCRIBER_BODY = "{\"firstName\":\"Claire\","
            + "\"lastName\":\"Dupont\",\"email\":\"claire.dupont@example.com\","
            + "\"birthDate\":\"1985-04-12\"}";

    private String draftId;

    @Soit("l'utilisateur dispose d'une session valide")
    public void theUserHoldsAValidCsrfPair() {
        Response issued = request().get("/api/v1/csrf-token");
        assertEquals(200, issued.statusCode(), issued.asString());
        assertNotNull(issued.jsonPath().getString("token"), "aucun jeton anti-CSRF emis");
    }

    @Soit("l'utilisateur a renseigne un souscripteur valide pour le produit {string}")
    public void aValidSubscriberHasBeenCaptured(String productCode) {
        GatewayUnderTest.ensureStarted(testPort());
        WireMockServer subscription = DownstreamWireMocks.server(SUBSCRIPTION_DOWNSTREAM);
        subscription.resetToDefaultMappings();
        subscription.resetRequests();

        Response started = CsrfTestPair.withCsrf()
                .contentType("application/json")
                .body("{\"productCode\":\"" + productCode + "\"}")
                .post("/api/v1/drafts");
        assertEquals(201, started.statusCode(), started.asString());
        this.draftId = started.jsonPath().getString("draftId");

        Response captured = CsrfTestPair.withCsrf()
                .contentType("application/json")
                .body(SUBSCRIBER_BODY)
                .put("/api/v1/drafts/" + draftId + "/subscriber");
        assertEquals(200, captured.statusCode(), captured.asString());
        recordResponse(captured);
    }

    /**
     * The amounts are valid, so the only defect of the submission is the legacy {@code command}
     * member. The write must not reach souscription-service.
     */
    @Quand("l'utilisateur soumet la simulation avec la commande {string}")
    public void submitTheSimulationWithACommand(String command) {
        recordResponse(CsrfTestPair.withCsrf()
                .contentType("application/json")
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10,\"command\":\"" + command + "\"}")
                .put("/api/v1/drafts/" + draftId + "/simulation"));
        long forwarded = DownstreamWireMocks.server(SUBSCRIPTION_DOWNSTREAM).getServeEvents()
                .getServeEvents().stream()
                .filter(event -> event.getRequest().getUrl().endsWith("/simulation"))
                .count();
        assertEquals(0, forwarded, "la commande inconnue ne doit pas etre transmise en aval");
    }

    @Quand("l'utilisateur appelle un chemin inconnu du parcours")
    public void callAnUnknownJourneyPath() {
        recordResponse(request().get(UNKNOWN_STEP_PATH));
        assertEquals("application/problem+json",
                String.valueOf(lastResponse().contentType()).split(";")[0].trim(),
                "un chemin inconnu doit etre refuse en problem+json");
    }
}
