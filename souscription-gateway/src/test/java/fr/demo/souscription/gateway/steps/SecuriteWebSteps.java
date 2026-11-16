package fr.demo.souscription.gateway.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Et;
import io.cucumber.java.fr.Quand;
import io.cucumber.java.fr.Soit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Gateway glue for {@code features/securite-web/securite-web.feature}: scenarios
 * « Entetes de securite ajoutes a chaque reponse » (W2-T01),
 * « L'encodage des requetes et des reponses est UTF-8 », « Methodes HTTP non autorisees » and
 * « Methodes HTTP autorisees » (W2-T02).
 *
 * <p>Assertions are made on HTTP response headers with REST Assured: the gateway serves JSON only,
 * so there is no HTML to scrape. Legacy {@code *.do} paths of the feature are translated to their
 * {@code /api/v1} equivalent (see {@code docs/routing-matrix.md}).
 *
 * <p>Allow-list deviation: the feature was written against the source method set
 * ({@code GET, HEAD, POST}) and expects 405 for {@code PUT}. The target API is resource-oriented and
 * accepts {@code PUT} on the draft sub-resources, so {@code gateway.security.allowed-methods}
 * includes it. The {@code Allow} assertion is therefore expressed against the configured allow-list
 * rather than against the literal of the feature; the decision is recorded in
 * {@code docs/edge-filters.md}.
 */
public class SecuriteWebSteps extends GatewayStepsSupport {

    /** Draft id used when a legacy path is translated to a draft sub-resource. */
    private static final String DRAFT_PLACEHOLDER = "draft-inconnu";

    private static final Map<String, String> LEGACY_PATHS = Map.of(
            "/catalogue.do", "/api/v1/products",
            "/commencer.do", "/api/v1/drafts",
            "/recapitulatif.do", "/api/v1/drafts/" + DRAFT_PLACEHOLDER,
            "/confirmer.do", "/api/v1/drafts/" + DRAFT_PLACEHOLDER + "/confirmation",
            "/confirmation.do", "/api/v1/subscriptions/reference-inconnue");

    private String lastMethod;

    /** Draft id of the translated submissions: a real UUID, so routing reaches the resource. */
    private static final String SUBMISSION_DRAFT_ID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

    /** Minimal structurally valid subscriber body for the translated submissions. */
    private static final String SUBSCRIBER_BODY = "{\"firstName\":\"Claire\","
            + "\"lastName\":\"Dupont\",\"email\":\"claire.dupont@example.com\","
            + "\"birthDate\":\"1985-04-12\"}";

    private static final Map<String, String> SUBMISSION_PATHS = Map.of(
            "/souscripteur.do", "/api/v1/drafts/" + SUBMISSION_DRAFT_ID + "/subscriber",
            "/simulation.do", "/api/v1/drafts/" + SUBMISSION_DRAFT_ID + "/simulation");

    /**
     * Token the scenario presents to the gateway, standing in for the former session attribute.
     * {@code null} means « la session ne contient aucun jeton »: the request is sent cookie-less.
     */
    private String presetToken;

    /** Label the feature gave to the held token, e.g. « jeton-valide ». */
    private String presetLabel;

    @Soit("l'application Horizon est demarree")
    public void applicationStarted() {
        GatewayUnderTest.ensureStarted(testPort());
    }

    /**
     * Reinterpretation of « la session de l'utilisateur ne contient aucun jeton anti-CSRF » for the
     * stateless gateway: no CSRF cookie is attached to the request (W3-T02).
     */
    @Soit("la session de l'utilisateur ne contient aucun jeton anti-CSRF")
    public void noCsrfTokenHeld() {
        this.presetToken = null;
    }

    /**
     * Reinterpretation of « la session contient le jeton anti-CSRF "X" »: the label of the feature
     * is not a token value any more — the gateway only honours tokens it signed — so a live token
     * is obtained from the endpoint and plays the role of the named one.
     */
    @Soit("la session de l'utilisateur contient le jeton anti-CSRF {string}")
    public void csrfTokenHeld(String label) {
        this.presetToken = io.restassured.RestAssured.given()
                .when().get("/api/v1/csrf-token")
                .then().statusCode(200)
                .extract().jsonPath().getString("token");
        assertNotNull(presetToken, "aucun jeton obtenu pour " + label);
        this.presetLabel = label;
    }

    @Quand("l'utilisateur appelle {string} en methode {string}")
    public void callPathWithMethod(String path, String method) {
        this.lastMethod = method.toUpperCase(Locale.ROOT);
        var spec = request();
        if (presetToken != null) {
            spec = spec.cookie(csrfCookieName(), presetToken);
        }
        if (isStateChangingAndAccepted(lastMethod)) {
            // These scenarios assert the method verdict, not the CSRF one: the request carries a
            // valid pair so the 405 (or its absence) is what is actually being observed.
            String token = presetToken != null ? presetToken : freshToken();
            spec = spec.cookie(csrfCookieName(), token).header(csrfHeaderName(), token);
        }
        recordResponse(spec.request(method, target(path)));
    }

    private static boolean isStateChangingAndAccepted(String method) {
        return !"GET".equals(method) && !"HEAD".equals(method)
                && configuredAllowedMethods().contains(method);
    }

    private static String freshToken() {
        return io.restassured.RestAssured.given().when().get("/api/v1/csrf-token")
                .then().statusCode(200).extract().jsonPath().getString("token");
    }

    /** The cookie is issued on a safe request when none is held. */
    @Alors("un jeton anti-CSRF est present en session")
    public void aCsrfTokenIsIssued() {
        String issued = issuedCsrfCookie();
        assertNotNull(issued, "aucun cookie anti-CSRF emis : "
                + lastResponse().getHeaders().getValues("Set-Cookie"));
        assertTrue(issued.length() <= configuredMaxTokenLength(),
                "le jeton depasse la borne conservee : " + issued.length());
    }

    /** An already-held token is never regenerated: no new cookie is issued. */
    @Alors("le jeton anti-CSRF en session reste {string}")
    public void theHeldCsrfTokenIsPreserved(String label) {
        assertNotNull(presetToken, "aucun jeton prealable pour " + label);
        String issued = issuedCsrfCookie();
        assertTrue(issued == null || presetToken.equals(issued),
                "le jeton existant a ete remplace : " + issued);
    }

    /**
     * Submission of a state-changing request with an explicit CSRF header (W3-T03). « Soumettre »
     * becomes {@code PUT /api/v1/drafts/{id}/subscriber} with the cookie held by the scenario and
     * the named token echoed in the {@code gateway.csrf.header-name} header. The empty token of the
     * outline is sent as an empty header value: a submitted but wrong token, as in the source.
     */
    @Quand("l'utilisateur soumet {string} avec le jeton anti-CSRF {string}")
    public void submitWithToken(String path, String token) {
        submit(path, submittedValue(token), true);
    }

    /**
     * The feature names tokens by label, not by value. The label of the held token stands for its
     * real value; any other label — including the empty one of the outline — is submitted verbatim
     * and is therefore a wrong token. Case matters: « JETON-VALIDE » is a different label and must
     * stay a rejection, which is exactly the byte comparison the source performed.
     */
    private String submittedValue(String label) {
        return label != null && label.equals(presetLabel) ? presetToken : label;
    }

    /** « sans jeton anti-CSRF »: the header is simply not emitted. */
    @Quand("l'utilisateur soumet {string} sans jeton anti-CSRF")
    public void submitWithoutToken(String path) {
        submit(path, null, false);
    }

    /** Preserved 100-character bound: a 101-character token is refused before any comparison. */
    @Quand("l'utilisateur soumet {string} avec un jeton anti-CSRF de 101 caracteres")
    public void submitWithOversizedToken(String path) {
        submit(path, "x".repeat(configuredMaxTokenLength() + 1), true);
    }

    /**
     * The request went past the CSRF check: it was not answered 403 at the edge. What the
     * downstream then answers is out of scope of this feature.
     */
    @Alors("la requete est transmise a l'action du parcours")
    public void theRequestReachedTheAction() {
        assertNotEquals(403, lastResponse().getStatusCode(),
                "la soumission portant le jeton attendu ne doit pas etre refusee : "
                        + lastResponse().getBody().asString());
    }

    /** The request was stopped at the edge: 403 problem+json, with no cause disclosed. */
    @Et("la requete n'est pas transmise a l'action du parcours")
    public void theRequestDidNotReachTheAction() {
        assertEquals(403, lastResponse().getStatusCode());
        assertTrue(String.valueOf(lastResponse().getContentType()).contains("problem+json"),
                "le refus doit etre en problem+json : " + lastResponse().getContentType());
        String body = lastResponse().getBody().asString().toLowerCase(Locale.ROOT);
        assertFalse(body.contains("cookie"), "le corps ne doit pas divulguer la cause : " + body);
    }

    /**
     * Static assets live outside {@code /api/v1}, the only scope of the check, so no token is
     * required: the source scoped its check on {@code *.do} and left static resources alone.
     */
    @Alors("aucune verification de jeton anti-CSRF n'est effectuee")
    public void noCsrfCheckIsPerformed() {
        assertNotEquals(403, lastResponse().getStatusCode(),
                "une ressource statique ne doit jamais etre refusee pour CSRF");
    }

    @Et("la requete est transmise a la chaine de traitement")
    public void theRequestReachedTheChain() {
        assertNotEquals(403, lastResponse().getStatusCode());
        assertNotNull(lastResponse().getHeader("X-Content-Type-Options"),
                "la chaine de traitement doit avoir ajoute les entetes de securite");
    }

    private void submit(String path, String token, boolean sendHeader) {
        this.lastMethod = "PUT";
        var spec = request().contentType("application/json").body(SUBSCRIBER_BODY);
        if (presetToken != null) {
            spec = spec.cookie(csrfCookieName(), presetToken);
        }
        if (sendHeader) {
            spec = spec.header(csrfHeaderName(), token == null ? "" : token);
        }
        recordResponse(spec.when().put(submissionTarget(path)));
    }

    /** Legacy submission paths translated to their state-changing {@code /api/v1} equivalent. */
    private static String submissionTarget(String path) {
        return SUBMISSION_PATHS.getOrDefault(path,
                "/api/v1/drafts/" + SUBMISSION_DRAFT_ID + "/subscriber");
    }

    private static String csrfHeaderName() {
        return ConfigProvider.getConfig().getValue("gateway.csrf.header-name", String.class);
    }

    @Alors("la reponse porte l'entete {string} avec la valeur {string}")
    public void headerHasValue(String name, String expected) {
        if ("Allow".equalsIgnoreCase(name)) {
            assertAllowHeader();
            return;
        }
        assertEquals(expected, lastResponse().getHeader(name), "entete " + name);
    }

    @Alors("le systeme repond avec le code HTTP {int}")
    public void statusIs(int expected) {
        assertEquals(expected, lastResponse().getStatusCode(),
                "corps de la reponse : " + lastResponse().getBody().asString());
    }

    @Alors("le systeme ne repond pas avec le code HTTP {int}")
    public void statusIsNot(int unexpected) {
        assertNotEquals(unexpected, lastResponse().getStatusCode());
    }

    @Alors("l'encodage de la requete est {string}")
    public void requestEncodingIs(String charset) {
        assertEquals(charset.toUpperCase(Locale.ROOT), configuredRequestCharset(),
                "quarkus.http.body.default-charset");
    }

    @Et("l'encodage de la reponse est {string}")
    public void responseEncodingIs(String charset) {
        String contentType = lastResponse().getContentType();
        assertNotNull(contentType, "entete Content-Type absent");
        assertTrue(contentType.toUpperCase(Locale.ROOT)
                        .contains(charset.toUpperCase(Locale.ROOT)),
                "l'encodage de la reponse doit etre " + charset + " : " + contentType);
    }

    @Et("la reponse porte une politique de securite du contenu restrictive")
    public void cspIsRestrictive() {
        String csp = lastResponse().getHeader("Content-Security-Policy");
        assertNotNull(csp, "entete Content-Security-Policy absent");
        assertEquals(configuredCsp(), csp, "la CSP doit venir de gateway.security.csp");
        assertTrue(csp.contains("default-src 'none'"), "default-src doit etre 'none' : " + csp);
        assertTrue(csp.contains("frame-ancestors 'none'"), "frame-ancestors doit etre 'none' : " + csp);
        assertTrue(csp.contains("base-uri 'none'"), "base-uri doit etre 'none' : " + csp);
        assertFalse(csp.contains("unsafe-inline"), "la CSP ne doit pas autoriser l'inline : " + csp);
        assertFalse(csp.contains("*"), "la CSP ne doit pas contenir de joker : " + csp);
    }

    /**
     * The 405 must advertise the methods the request may use: every method listed must belong to
     * the configured allow-list, and the rejected method must not be listed.
     *
     * <p>Documented deviation: for a method the edge allow-list <em>accepts</em> but the targeted
     * resource does not declare — {@code PUT} on {@code /api/v1/products} in this outline — the 405
     * comes from routing, not from the allow-list filter, and carries no {@code Allow} header. Only
     * the normalised problem+json 405 is asserted in that case. See {@code docs/edge-filters.md}.
     */
    private void assertAllowHeader() {
        List<String> configured = configuredAllowedMethods();
        if (configured.contains(lastMethod)) {
            assertEquals(405, lastResponse().getStatusCode());
            assertTrue(String.valueOf(lastResponse().getContentType()).contains("problem+json"),
                    "le 405 de routage doit rester en problem+json : " + lastResponse().getContentType());
            return;
        }
        String allow = lastResponse().getHeader("Allow");
        assertNotNull(allow, "entete Allow absent sur la reponse 405");
        List<String> advertised = Arrays.stream(allow.split(",")).map(String::trim).toList();
        advertised.forEach(method -> assertTrue(configured.contains(method),
                "methode annoncee hors de gateway.security.allowed-methods : " + method));
        assertFalse(advertised.contains(lastMethod),
                "la methode refusee ne doit pas figurer dans Allow : " + allow);
    }

    private static List<String> configuredAllowedMethods() {
        return List.of(ConfigProvider.getConfig()
                .getValue("gateway.security.allowed-methods", String[].class));
    }

    private static String configuredRequestCharset() {
        return ConfigProvider.getConfig()
                .getValue("quarkus.http.body.default-charset", String.class)
                .toUpperCase(Locale.ROOT);
    }

    private static String configuredCsp() {
        return ConfigProvider.getConfig().getValue("gateway.security.csp", String.class);
    }

    private static String csrfCookieName() {
        return ConfigProvider.getConfig().getValue("gateway.csrf.cookie-name", String.class);
    }

    private static int configuredMaxTokenLength() {
        return ConfigProvider.getConfig()
                .getValue("gateway.csrf.max-token-length", Integer.class);
    }

    /** Value of the CSRF cookie set by the last response, or {@code null} when none was set. */
    private String issuedCsrfCookie() {
        String prefix = csrfCookieName() + "=";
        for (String header : lastResponse().getHeaders().getValues("Set-Cookie")) {
            if (header.startsWith(prefix)) {
                String first = header.split(";", 2)[0];
                return first.substring(first.indexOf('=') + 1);
            }
        }
        return null;
    }

    private static String target(String path) {
        return LEGACY_PATHS.getOrDefault(path, path);
    }
}
