package fr.demo.souscription.gateway.steps;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Et;
import io.cucumber.java.fr.Quand;
import io.cucumber.java.fr.Soit;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Gateway glue for the two transport scenarios of {@code features/simulation/simulation.feature}
 * (W4-T05): « Simulation d'un placement standard » and
 * « Parametres de simulation rejetes par le moteur de calcul ».
 *
 * <p>What the gateway owns on this feature is the <strong>transport</strong>, not the arithmetic:
 * it resolves the product's authoritative annual rate from the catalogue, forwards it to
 * souscription-simulation-service and relays the answer — the projection on success, and the
 * engine's {@code FieldErrors} verbatim in the {@code errors} member of the problem document on
 * rejection. The eight computation and input-validation scenarios of the same feature are tagged
 * {@code @souscription-simulation-service} and are not run here (see
 * {@code features/SCENARIO_OWNERSHIP.md}).
 *
 * <p>The engine itself is therefore stubbed: its answer is given by the downstream WireMock double,
 * so a change in the compound-interest formula cannot make a gateway gate fail.
 */
public class SimulationSteps extends GatewayStepsSupport {

    private static final String SIMULATION_DOWNSTREAM = "simulation";

    private static final String DOWNSTREAM_PATH = "/v1/simulations";

    /** Label the feature expects when the engine rejects the parameters. */
    private static final String REJECTION_LABEL = "Paramètres de simulation invalides";

    private String productCode;
    private BigDecimal annualRate;

    /** {@code true} when the last call was answered by the engine stub rather than rejected locally. */
    private boolean engineWasCalled;

    @Soit("le produit retenu est {string} avec un taux annuel de {string}")
    public void theRetainedProductCarriesTheRate(String code, String rate) {
        GatewayUnderTest.ensureStarted(testPort());
        this.productCode = code;
        this.annualRate = new BigDecimal(rate);
        engine().resetToDefaultMappings();
        engine().resetRequests();
    }

    /**
     * Nominal path: the engine answers the projection and the gateway relays it unchanged. The
     * stubbed projection is derived from the submitted amounts so the feature's own arithmetic
     * assertions stay meaningful at this edge.
     */
    @Quand("le systeme calcule la simulation avec un initial de {string}, une mensualite de {string} et une duree de {int} ans")
    public void theSystemComputesTheSimulation(String initial, String monthly, int years) {
        BigDecimal initialAmount = amount(initial);
        BigDecimal monthlyAmount = amount(monthly);
        BigDecimal contributed = initialAmount
                .add(monthlyAmount.multiply(BigDecimal.valueOf(years * 12L)))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal finalCapital = contributed
                .multiply(BigDecimal.ONE.add(annualRate).pow(years, MathContext.DECIMAL64))
                .setScale(2, RoundingMode.HALF_UP);
        stubEngine(200, "{\"initialAmount\":\"" + initialAmount.setScale(2, RoundingMode.HALF_UP)
                + "\",\"monthlyAmount\":\"" + monthlyAmount.setScale(2, RoundingMode.HALF_UP)
                + "\",\"durationYears\":" + years
                + ",\"contributed\":\"" + contributed
                + "\",\"finalCapital\":\"" + finalCapital + "\"}");

        recordResponse(simulate(quoted(initialAmount.toPlainString()),
                quoted(monthlyAmount.toPlainString()), years));
        this.engineWasCalled = true;
    }

    /**
     * Error path: the engine rejects the parameters and answers the shared {@code FieldErrors}
     * payload. Rows whose amounts are absent are rejected by the gateway's own structural
     * validation before any call — both verdicts are a 400 carrying the rejected fields.
     */
    @Quand("le systeme calcule la simulation avec les parametres bruts initial {string}, mensualite {string}, duree {int} et taux {string}")
    public void theSystemComputesWithRawParameters(String initial, String monthly, int years,
            String rate) {
        stubEngine(400, "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                + "\"detail\":\"" + REJECTION_LABEL + "\",\"errors\":["
                + "{\"field\":\"simulation\",\"message\":\"" + REJECTION_LABEL + "\","
                + "\"code\":\"invalid-parameters\"}]}");

        recordResponse(simulate(jsonAmount(initial), jsonAmount(monthly), years));
        this.engineWasCalled = engine().getServeEvents().getServeEvents().stream()
                .anyMatch(event -> event.getRequest().getUrl().startsWith(DOWNSTREAM_PATH));
    }

    @Alors("la simulation retourne un versement initial de {string}")
    public void theProjectionCarriesTheInitialAmount(String expected) {
        assertEquals(200, lastResponse().statusCode(), lastResponse().asString());
        assertAmount(expected, "initialAmount");
        // The rate is never supplied by the client: the gateway sends the catalogue's own value.
        assertTrue(engine().getServeEvents().getServeEvents().stream()
                        .anyMatch(event -> event.getRequest().getBodyAsString()
                                .contains("\"annualRate\":\"" + annualRate.setScale(6,
                                        RoundingMode.UNNECESSARY).toPlainString() + "\"")),
                "le taux autoritatif du produit " + productCode + " doit etre transmis au moteur");
    }

    @Et("la simulation retourne une mensualite de {string}")
    public void theProjectionCarriesTheMonthlyAmount(String expected) {
        assertAmount(expected, "monthlyAmount");
    }

    @Et("la simulation retourne une duree de {int} ans")
    public void theProjectionCarriesTheDuration(int expected) {
        assertEquals(expected, lastResponse().jsonPath().getInt("durationYears"));
    }

    @Et("le total verse de la simulation est {string}")
    public void theContributedAmountIs(String expected) {
        assertAmount(expected, "contributed");
    }

    @Et("le capital final de la simulation est superieur au total verse")
    public void theFinalCapitalExceedsTheContributedAmount() {
        assertTrue(decimal("finalCapital").compareTo(decimal("contributed")) > 0,
                "capital final " + decimal("finalCapital") + " <= total verse "
                        + decimal("contributed"));
    }

    @Et("les gains de la simulation sont egaux au capital final moins le total verse")
    public void theGainIsTheDifference() {
        // The published contract carries no "gains" member on purpose: it is derived by
        // souscription-web from the two amounts the gateway relays. What is asserted here is that
        // the two operands are present and consistent, and that no third amount is invented.
        assertNull(lastResponse().jsonPath().get("gains"),
                "les gains se derivent du capital final et du total verse, ils ne sont pas publies");
        assertTrue(decimal("finalCapital").subtract(decimal("contributed"))
                        .compareTo(BigDecimal.ZERO) >= 0,
                "capital final et total verse incoherents");
    }

    /**
     * The engine's rejection reaches the caller as a 400 {@code problem+json} whose {@code errors}
     * member carries the engine's own messages verbatim — the only downstream payload allowed on
     * the public surface (W4-T05).
     */
    @Alors("une erreur {string} est levee")
    public void anErrorIsRaised(String label) {
        assertEquals(400, lastResponse().statusCode(), lastResponse().asString());
        assertTrue(String.valueOf(lastResponse().contentType()).contains("problem+json"),
                "le refus doit etre en problem+json : " + lastResponse().contentType());
        List<Map<String, String>> errors = lastResponse().jsonPath().getList("errors");
        assertNotNull(errors, "le refus doit nommer les champs rejetes : "
                + lastResponse().asString());
        assertTrue(!errors.isEmpty(), "aucun champ rejete : " + lastResponse().asString());
        if (engineWasCalled) {
            assertEquals(List.of(Map.of("field", "simulation", "message", label,
                            "code", "invalid-parameters")), errors,
                    "le message du moteur doit etre relaye tel quel : " + errors);
        }
    }

    private io.restassured.response.Response simulate(String initial, String monthly, int years) {
        String body = "{\"productCode\":\"" + productCode + "\",\"initialAmount\":" + initial
                + ",\"monthlyAmount\":" + monthly + ",\"durationYears\":" + years + "}";
        return fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf()
                .contentType("application/json").body(body)
                .post("/api/v1/simulations");
    }

    private void stubEngine(int status, String body) {
        engine().resetRequests();
        engine().stubFor(post(urlPathEqualTo(DOWNSTREAM_PATH)).atPriority(1)
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", status >= 400
                                ? "application/problem+json" : "application/json")
                        .withBody(body)));
    }

    private static WireMockServer engine() {
        return DownstreamWireMocks.server(SIMULATION_DOWNSTREAM);
    }

    /** « 1000,00 » and « 1000.00 » are the same amount: the comma is the source's decimal mark. */
    private static BigDecimal amount(String raw) {
        return new BigDecimal(raw.trim().replace(',', '.'));
    }

    /** A raw cell as a JSON value: an absent amount travels as {@code null}. */
    private static String jsonAmount(String raw) {
        return raw == null || raw.isBlank() ? "null" : quoted(raw.trim().replace(',', '.'));
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    private void assertAmount(String expected, String member) {
        assertEquals(0, new BigDecimal(expected).compareTo(decimal(member)),
                member + " attendu " + expected + ", recu " + decimal(member));
    }

    private BigDecimal decimal(String member) {
        String value = lastResponse().jsonPath().getString(member);
        assertNotNull(value, member + " absent de " + lastResponse().asString());
        return new BigDecimal(value);
    }
}
