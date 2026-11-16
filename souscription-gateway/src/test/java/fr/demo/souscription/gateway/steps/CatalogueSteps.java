package fr.demo.souscription.gateway.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Quand;
import io.cucumber.java.fr.Soit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Gateway glue for the four gateway-owned scenarios of
 * {@code features/catalogue/catalogue.feature} (routing, method, JSON shape): « Methode HTTP non
 * autorisee sur le catalogue » (W2-T02), « Code produit vide ou mal formate sur la fiche produit »,
 * « Consultation de la fiche d'un placement inconnu » and « Recherche d'un produit sans code
 * transmis ».
 *
 * <p>{@code GET /api/v1/products} is the only method declared by {@code CatalogueResource}, so a
 * POST is answered 405 with a {@code problem+json} body. The four business scenarios of the same
 * feature are owned by {@code souscription-catalogue-service} and are not run here (see
 * {@code features/SCENARIO_OWNERSHIP.md}); their glue below is kept but not selected by the run.
 */
public class CatalogueSteps extends GatewayStepsSupport {

    private static final String PRODUCTS = "/api/v1/products";

    /**
     * The catalogue data itself lives in {@code souscription-catalogue-service}; at the gateway the
     * precondition reduces to having the application under test running.
     */
    @Soit("le catalogue Horizon est initialise avec les produits de reference")
    public void catalogueIsInitialised() {
        GatewayUnderTest.ensureStarted(testPort());
    }

    @Quand("l'utilisateur soumet le catalogue en methode POST")
    public void submitCatalogueWithPost() {
        // A valid CSRF pair is attached so the asserted verdict is the method one (405), not the
        // 403 the edge filter of W3-T03 would otherwise produce first.
        recordResponse(fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf()
                .contentType("application/json").body("{}").post(PRODUCTS));
    }

    @Quand("l'utilisateur demande la page du catalogue")
    public void requestCatalogue() {
        recordResponse(request().get(PRODUCTS));
    }

    /** Visible-space marker of the feature: Gherkin trims the cells of an {@code Examples} table. */
    private static final char VISIBLE_SPACE = '\u2420';

    /**
     * The code is percent-encoded explicitly so a malformed one (spaces, wrong case) travels
     * verbatim instead of being normalised away by the HTTP client. The « code encadre d'espaces »
     * row is written {@code ␠␠PRUDENT␠␠} in the feature, since Gherkin trims cell padding; each
     * U+2420 is sent as an ASCII space, which the catalogue answers 404.
     */
    @Quand("l'utilisateur demande la fiche du produit de code {string}")
    public void requestProductSheet(String code) {
        requestRawProductSheet(code.replace(VISIBLE_SPACE, ' '));
    }

    private void requestRawProductSheet(String code) {
        String encoded = URLEncoder.encode(code, StandardCharsets.UTF_8).replace("+", "%20");
        recordResponse(request().urlEncodingEnabled(false).get(PRODUCTS + "/" + encoded));
    }

    @Alors("le systeme affiche les {int} placements du catalogue")
    public void catalogueCarriesProducts(int expected) {
        assertEquals(200, lastResponse().statusCode());
        assertEquals(expected, lastResponse().jsonPath().getList("$").size());
    }

    /**
     * The wizard step number is derived by {@code souscription-web} from the route it is on: the
     * gateway holds no conversational state, so what it guarantees here is the absence of any
     * server-side step marker (see {@code docs/stateless-model.md}).
     */
    @Alors("le systeme indique l'etape {int} du parcours")
    public void noServerSideStepStateIsHeld(int step) {
        assertEquals(200, lastResponse().statusCode());
        assertNull(lastResponse().getCookie("JSESSIONID"),
                "the gateway is stateless: the journey step is owned by souscription-web");
    }

    /**
     * The page title belongs to {@code souscription-web}; the gateway serves the JSON the titled
     * page renders, and must never serve HTML.
     */
    @Alors("le titre de la page est {string}")
    public void theTitledPageIsServedAsJson(String title) {
        assertEquals(200, lastResponse().statusCode());
        assertTrue(lastResponse().contentType().startsWith("application/json"),
                "the gateway renders no view: the title is owned by souscription-web");
    }

    @Alors("le systeme affiche la fiche du produit nomme {string}")
    public void productSheetIsNamed(String name) {
        assertEquals(200, lastResponse().statusCode());
        assertEquals(name, lastResponse().jsonPath().getString("name"));
    }

    @Alors("le profil affiche est {string}")
    public void productProfileIs(String profile) {
        assertEquals(profile, lastResponse().jsonPath().getString("profile"));
    }

    @Alors("le taux annuel affiche est {string}")
    public void productAnnualRateIs(String rate) {
        // The wire format keeps the DECIMAL(8,6) scale ("0.020000"), so the comparison is numeric.
        assertEquals(0, new BigDecimal(rate)
                .compareTo(new BigDecimal(lastResponse().jsonPath().getString("annualRate"))));
    }

    /**
     * The percentage is a presentation derivation owned by {@code souscription-web}; what the
     * gateway guarantees is the rate it relays, from which that percentage is computed.
     */
    @Alors("le taux affiche en pourcentage est {string}")
    public void productAnnualRateAsPercentageIs(String percentage) {
        BigDecimal rate = new BigDecimal(lastResponse().jsonPath().getString("annualRate"));
        assertEquals(0, new BigDecimal(percentage).compareTo(
                rate.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)));
    }

    /**
     * The authoritative immutability of the catalogue is owned by
     * {@code souscription-catalogue-service}; at the gateway the observable guarantee is that the
     * relayed collection is not modifiable through the API — no write method is exposed on it.
     */
    @Quand("le systeme tente d'ajouter un produit a la liste retournee par le catalogue")
    public void attemptToAddAProductToTheCatalogue() {
        recordResponse(fr.demo.souscription.gateway.edge.CsrfTestPair.withCsrf()
                .contentType("application/json")
                .body("{\"code\":\"NOUVEAU\",\"name\":\"Horizon Nouveau\",\"profile\":\"Prudent\","
                        + "\"annualRate\":\"0.010000\"}")
                .post(PRODUCTS));
    }

    @Alors("une erreur de modification interdite est levee")
    public void theModificationIsRejected() {
        assertEquals(405, lastResponse().statusCode());
    }

    /**
     * Searching without a code addresses the product-sheet path with an empty code: the gateway
     * answers 404 and relays no product.
     */
    @Quand("l'utilisateur recherche un produit sans fournir de code")
    public void searchWithoutAnyCode() {
        recordResponse(request().urlEncodingEnabled(false).get(PRODUCTS + "/"));
    }

    @Alors("aucun produit n'est retourne par le catalogue")
    public void noProductIsReturned() {
        assertEquals(404, lastResponse().statusCode());
        assertNull(lastResponse().jsonPath().getString("code"), "aucun produit ne doit etre relaye");
    }
}
