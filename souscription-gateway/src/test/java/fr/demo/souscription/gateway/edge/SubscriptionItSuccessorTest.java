package fr.demo.souscription.gateway.edge;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import fr.demo.souscription.gateway.observability.RejectionMetrics;
import fr.demo.souscription.gateway.runtime.GatewayConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Successors of the non-CSRF gateway-owned assertions of the legacy {@code SubscriptionIT}
 * (W7-T02, CFG-013): method allow-list with {@code Allow}, the five security headers, the cookie
 * model, the not-found surface, the catalogue relay, the stale-confirmation conflict and the
 * documented payload of a downstream failure. JSON only, no HTML parsing; the three downstreams are
 * the contract-derived WireMock doubles.
 *
 * <p>Mapping to {@code SubscriptionIT} lines is listed in {@code docs/assertion-successors.md}.
 */
@QuarkusTest
class SubscriptionItSuccessorTest {

    private static final String SIMULATE = "/api/v1/simulations";

    private static final String SIMULATION_BODY = "{\"productCode\":\"EQUILIBRE\","
            + "\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\",\"durationYears\":5}";

    /** Text written by the downstream stub: it must never reach the public surface. */
    private static final String INTERNAL_MARKER = "java.lang.IllegalStateException: trace-interne";

    @Inject
    GatewayConfig config;

    @Inject
    MeterRegistry registry;

    private RejectionObservation observation;

    @BeforeEach
    void observe() {
        DownstreamWireMocks.CONFIG_KEYS.forEach(DownstreamWireMocks::bringBack);
        observation = RejectionObservation.start(registry);
    }

    @AfterEach
    void release() {
        observation.stop();
        DownstreamWireMocks.server("simulation").resetToDefaultMappings();
    }

    private static String expectedAllow() {
        return String.join(", ", org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getValue("gateway.security.allowed-methods", String[].class));
    }

    /** SubscriptionIT l.125: a forbidden method is 405, and the answer carries {@code Allow}. */
    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "PATCH", "OPTIONS", "TRACE"})
    void forbiddenMethodIs405WithAllow(String method) {
        double before = observation.count(405, RejectionMetrics.METHOD);
        String instance = given().when().request(method, "/api/v1/drafts/" + UUID.randomUUID())
                .then().statusCode(405)
                .header("Allow", expectedAllow())
                .contentType("application/problem+json")
                .body("status", equalTo(405))
                .body("type", containsString("method-not-allowed"))
                .extract().jsonPath().getString("instance");
        observation.assertSingleRejection(405, RejectionMetrics.METHOD, before, instance);
    }

    /**
     * SubscriptionIT l.125 (GET on the POST-only {@code commencer.do}): an allowed method on a route
     * that does not declare it is 405 with the route's {@code Allow}. It is decided by the resource,
     * not by an edge filter, so no {@code gateway.rejections} series applies: it is journalised as
     * one problem entry.
     */
    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/drafts|POST", "POST /api/v1/products|GET, HEAD"})
    void undeclaredMethodOnARouteIs405WithAllow(String scenario) {
        String[] request = scenario.split("\\|")[0].split(" ");
        String allow = scenario.split("\\|")[1];
        String instance = CsrfTestPair.withCsrf().contentType(ContentType.JSON).body("{}")
                .when().request(request[0], request[1])
                .then().statusCode(405)
                .header("Allow", equalTo(allow))
                .contentType("application/problem+json")
                .extract().jsonPath().getString("instance");
        observation.assertSingleProblemEntry(405, instance);
    }

    /**
     * SubscriptionIT l.32-33 and l.169: the five security headers, with {@code no-store} and a CSP
     * that forbids script, on a 200, a 403, a 405, a router 404 and a downstream failure.
     */
    @Test
    void theFiveSecurityHeadersAreOnEveryResponseClass() {
        assertFiveHeaders(given().when().get("/api/v1/products"), 200);
        assertFiveHeaders(given().contentType(ContentType.JSON).body("{}")
                .when().post("/api/v1/drafts"), 403);
        assertFiveHeaders(given().when().delete("/api/v1/products"), 405);
        assertFiveHeaders(given().when().get("/console"), 404);
        downstreamAnswers(500);
        assertFiveHeaders(CsrfTestPair.withCsrf().contentType(ContentType.JSON).body(SIMULATION_BODY)
                .when().post(SIMULATE), 502);
    }

    private void assertFiveHeaders(Response response, int status) {
        response.then().statusCode(status)
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", config.security().csp())
                .header("Cache-Control", "no-store");
        String csp = response.header("Content-Security-Policy");
        assertTrue(csp.contains("default-src 'none'") || csp.contains("script-src 'none'"),
                "the CSP must forbid script, as script-src 'none' did: " + csp);
    }

    /**
     * SubscriptionIT l.34-35: the session cookie ({@code HttpOnly}, {@code SameSite=Lax}) is gone by
     * design. Its successor is: no session cookie at all, and a {@code Secure},
     * {@code SameSite=Strict} CSRF cookie that the SPA must read (hence no {@code HttpOnly}).
     */
    @Test
    void noSessionCookieAndAStrictCsrfCookie() {
        Response response = given().when().get("/api/v1/products");
        response.then().statusCode(200);
        List<String> setCookies = response.headers().getValues("Set-Cookie");
        assertTrue(setCookies.stream().noneMatch(c -> c.startsWith("JSESSIONID")),
                "no session cookie may be issued: " + setCookies);
        String csrf = setCookies.stream()
                .filter(c -> c.startsWith(config.csrf().cookieName() + "=")).findFirst()
                .orElseThrow(() -> new AssertionError("no CSRF cookie: " + setCookies));
        String lower = csrf.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("samesite=strict"), csrf);
        assertTrue(lower.contains("secure"), csrf);
        assertFalse(lower.contains("httponly"), csrf);
        observation.assertNoRejection();
    }

    /** SubscriptionIT l.24 and l.36-41: the catalogue lists the three products, each one readable. */
    @Test
    void catalogueListsThreeProductsEachReadable() {
        given().when().get("/api/v1/products")
                .then().statusCode(200).contentType(ContentType.JSON).body("$", hasSize(3));
        for (String code : List.of("PRUDENT", "EQUILIBRE", "DYNAMIQUE")) {
            given().when().get("/api/v1/products/{code}", code)
                    .then().statusCode(200).body("code", equalTo(code));
        }
    }

    /** SubscriptionIT l.42-44: unknown product, server-side views and the H2 console are 404. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/products/INCONNU", "/WEB-INF/jsp/recapitulatif.jsp", "/console"})
    void notFoundSurface(String path) {
        String instance = given().when().get(path)
                .then().statusCode(404).contentType("application/problem+json")
                .body("status", equalTo(404))
                .extract().jsonPath().getString("instance");
        observation.assertSingleProblemEntry(404, instance);
    }

    /**
     * SubscriptionIT l.114-115: a stale confirmation key is 409 and the answer discloses no
     * exception.
     */
    @Test
    void staleConfirmationKeyIs409WithoutInternalDetail() {
        Response response = CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .body(Map.of("confirmationKey", "00000000-0000-0000-0000-000000000000"))
                .when().post("/api/v1/drafts/{id}/confirmation", "11111111-1111-1111-1111-111111111111");
        response.then().statusCode(409).contentType("application/problem+json")
                .body("status", equalTo(409));
        assertFalse(response.asString().contains("Exception"), response.asString());
        observation.assertSingleProblemEntry(409, response.jsonPath().getString("instance"));
    }

    /** Downstream failure: 5xx becomes the documented 502 payload, with no downstream text. */
    @Test
    void downstreamFailureReturnsTheDocumentedPayload() {
        downstreamAnswers(500);
        Response response = CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .body(SIMULATION_BODY).when().post(SIMULATE);
        response.then().statusCode(502).contentType("application/problem+json")
                .body("status", equalTo(502))
                .body("type", containsString("downstream-error"))
                .body("title", not(equalTo(null)))
                .body("instance", not(equalTo(null)));
        assertFalse(response.asString().contains(INTERNAL_MARKER), response.asString());
        assertFalse(response.asString().contains("Exception"), response.asString());
        observation.assertSingleProblemEntry(502, response.jsonPath().getString("instance"));
    }

    /** Downstream 503: the documented 503 payload with {@code Retry-After}. */
    @Test
    void downstreamUnavailableReturnsTheDocumentedPayload() {
        downstreamAnswers(503);
        Response response = CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .body(SIMULATION_BODY).when().post(SIMULATE);
        response.then().statusCode(503).contentType("application/problem+json")
                .header("Retry-After", "30")
                .body("type", containsString("service-unavailable"));
        assertFalse(response.asString().contains(INTERNAL_MARKER), response.asString());
        observation.assertSingleProblemEntry(503, response.jsonPath().getString("instance"));
    }

    private static void downstreamAnswers(int status) {
        DownstreamWireMocks.server("simulation").stubFor(post(urlPathEqualTo("/v1/simulations"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"type\":\"about:blank\",\"title\":\"Error\",\"status\":" + status
                                + ",\"detail\":\"" + INTERNAL_MARKER + "\"}")));
    }
}
