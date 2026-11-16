package fr.demo.souscription.gateway.edge;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.demo.souscription.gateway.observability.RejectionMetrics;
import fr.demo.souscription.gateway.runtime.GatewayConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * Parity skeleton for 16-souscription-gateway-csrf-double-submit-filter.
 *
 * <p>The source enforced CSRF on {@code POST} only, inside {@code path.endsWith(".do")}. The target
 * extends enforcement to every state-changing method over {@code /api/v1/**} — the measurable
 * criterion is NFG-002: zero state-changing request accepted without a valid token pair. The second
 * property is non-disclosure: the six rejection cases must return the same body, so an attacker
 * cannot tell "missing" from "expired".
 */
@QuarkusTest
class CsrfMatrixParityTest {

    private static final Path FILTER_SOURCE = Path.of("src", "main", "java", "fr", "demo",
            "souscription", "gateway", "edge", "CsrfDoubleSubmitFilter.java");
    private static final Path TRUST_MODEL = Path.of("docs", "csrf-trust-model.md");
    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    private static final String DRAFTS = "/api/v1/drafts";
    private static final String BODY = "{\"productCode\":\"AUTO-TIERS\"}";

    @Inject
    GatewayConfig config;

    @Inject
    MeterRegistry registry;

    private final ObjectMapper json = new ObjectMapper();

    /** Step 1: the six rejection cases are all answered 403 problem+json. */
    @Test
    void theSixRejectionCasesAreAllRefused() {
        String token = freshToken();
        String other = freshToken();
        String expired = expiredToken();
        assertRejected("cookie absent + header absent",
                missing(() -> post(null, null)));
        assertRejected("cookie absent + header present",
                missing(() -> post(null, token)));
        assertRejected("cookie present + header absent",
                missing(() -> post(token, null)));
        assertRejected("differing values", mismatch(() -> post(token, other)));
        assertRejected("header longer than the bound",
                mismatch(() -> post(token, "x".repeat(config.csrf().maxTokenLength() + 1))));
        assertRejected("expired token", mismatch(() -> post(expired, expired)));

        Response accepted = post(token, token);
        assertNotEquals(403, accepted.getStatusCode(),
                "a valid matched pair must pass the filter: " + accepted.getBody().asString());
    }

    /** Step 2: every state-changing method is enforced, none ever reaches a 2xx without a pair. */
    @Test
    void noStateChangingMethodIsAcceptedWithoutAPair() {
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            RejectionObservation observation = RejectionObservation.start(registry);
            Response response;
            try {
                double csrfBefore = observation.count(403, RejectionMetrics.CSRF_MISSING);
                double methodBefore = observation.count(405, RejectionMetrics.METHOD);
                response = send(method, DRAFTS + "/" + UUID.randomUUID(), null, null);
                String instance = response.jsonPath().getString("instance");
                if (response.getStatusCode() == 405) {
                    observation.assertSingleRejection(405, RejectionMetrics.METHOD, methodBefore,
                            instance);
                } else {
                    observation.assertSingleRejection(403, RejectionMetrics.CSRF_MISSING,
                            csrfBefore, instance);
                }
            } finally {
                observation.stop();
            }
            int status = response.getStatusCode();
            assertTrue(status == 403 || status == 405,
                    method + " without a pair must be refused (403 by CSRF or 405 by the "
                            + "allow-list), got " + status + ": " + response.getBody().asString());
            assertFalse(status >= 200 && status < 300,
                    method + " without a pair reached a 2xx: NFG-002 violated");
        }
        String filter = read(FILTER_SOURCE);
        assertFalse(filter.contains("\"POST\".equals("),
                "the filter must be driven by the safe-method set, not by a POST-only test");
        assertTrue(filter.contains("Set.of(\"GET\", \"HEAD\")"),
                "the enforced set must be the complement of the safe methods");
    }

    /** Step 3: safe methods are never rejected and still receive a cookie when absent. */
    @Test
    void safeMethodsAreNeverRejectedAndReceiveACookie() {
        Response get = given().when().get("/api/v1/products");
        assertNotEquals(403, get.getStatusCode(), "a GET must never be CSRF-rejected");
        assertNotEquals(403, given().when().head("/api/v1/products").getStatusCode(),
                "a HEAD must never be CSRF-rejected");

        List<String> cookies = get.getHeaders().getValues("Set-Cookie");
        assertTrue(cookies.stream().anyMatch(this::isCsrfCookie),
                "a safe request without cookie must be issued one: " + cookies);
    }

    /** Step 4: non-disclosure — the six bodies are identical apart from {@code instance}. */
    @Test
    void theSixRejectionBodiesAreIndistinguishable() {
        String token = freshToken();
        String other = freshToken();
        String expired = expiredToken();
        Set<String> bodies = new LinkedHashSet<>();
        bodies.add(normalisedBody(missing(() -> post(null, null))));
        bodies.add(normalisedBody(missing(() -> post(null, token))));
        bodies.add(normalisedBody(missing(() -> post(token, null))));
        bodies.add(normalisedBody(mismatch(() -> post(token, other))));
        bodies.add(normalisedBody(mismatch(
                () -> post(token, "x".repeat(config.csrf().maxTokenLength() + 1)))));
        bodies.add(normalisedBody(mismatch(() -> post(expired, expired))));

        assertEquals(1, bodies.size(),
                "the six rejections must share one body, none disclosing the cause: " + bodies);
        String body = bodies.iterator().next().toLowerCase(Locale.ROOT);
        assertFalse(body.contains("cookie"), "the body must not mention the cookie: " + body);
        assertFalse(body.contains("expir"), "the body must not disclose expiry: " + body);
        assertFalse(body.contains(config.csrf().headerName().toLowerCase(Locale.ROOT)),
                "the body must not mention the header: " + body);
    }

    /** Step 5: enforcement scope is /api/v1/** and the management endpoints are excluded. */
    @Test
    void scopeIsTheApiOnlyAndManagementIsExcluded() {
        String filter = read(FILTER_SOURCE);
        assertTrue(filter.contains("/api/v1"),
                "the scope must be /api/v1/**, replacing the source's .do suffix test");
        String code = filter.substring(filter.indexOf("public class CsrfDoubleSubmitFilter"));
        assertFalse(code.contains("endsWith(\".do\")"),
                "the .do suffix scope of the source must not survive in the code");
        assertTrue(code.contains("PROTECTED_PREFIX = \"/api/v1\"")
                        && code.contains("startsWith(PROTECTED_PREFIX)"),
                "the enforcement must be limited to the /api/v1 prefix: " + code);

        assertNotEquals(403, given().when().get("/assets/css/application.css").getStatusCode(),
                "static resources carry no CSRF requirement");

        int managementPort = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class).orElse(9001);
        for (String probe : List.of("/q/health", "/q/metrics")) {
            assertNotEquals(403, given().port(managementPort).when().get(probe).getStatusCode(),
                    probe + " must not be CSRF-guarded: the Kubernetes probes depend on it");
        }
    }

    /** Step 6: the five deviations and the same-origin dependency are documented. */
    @Test
    void theTrustModelDocumentsTheFiveDeviations() {
        String document = read(TRUST_MODEL).toLowerCase(Locale.ROOT);
        for (String marker : List.of("httponly", "samesite=strict", "x-csrf-token",
                "put", "patch", "delete", "ttl", "od-004")) {
            assertTrue(document.contains(marker),
                    "docs/csrf-trust-model.md must document '" + marker + "'");
        }
        assertTrue(document.contains("meme origine") || document.contains("same origin"),
                "the SameSite=Strict choice must state its same-origin dependency (OD-004)");
    }

    /** Step 7: the contract declares the CSRF header as required on state-changing operations. */
    @Test
    void theContractDeclaresTheCsrfHeaderOnStateChangingOperations() {
        int managementPort = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class).orElse(9001);
        String document = given().port(managementPort).accept("application/json")
                .when().get("/q/openapi").then().statusCode(200)
                .extract().body().asString();
        assertTrue(document.contains("csrfToken"),
                "the csrfToken security scheme must be published");
        assertTrue(document.contains(config.csrf().headerName()),
                "the document must name the " + config.csrf().headerName() + " header");
        assertTrue(document.contains("startDraft") && document.contains("\"403\""),
                "state-changing operations must declare the 403 response");
    }

    /** Step 8: no lock was introduced anywhere in the main sources (FG-003, AG-015). */
    @Test
    void noLockGuardsTheEdge() throws Exception {
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> read(p).replace("{@code synchronized}", "")
                            .replace("synchronized(session)", "")
                            .contains("synchronized"))
                    .map(Path::toString)
                    .toList();
            assertTrue(offenders.isEmpty(), "no lock may guard the edge: " + offenders);
        }
    }

    private String freshToken() {
        String token = given().when().get("/api/v1/csrf-token")
                .then().statusCode(200)
                .extract().jsonPath().getString("token");
        assertNotNull(token, "no token issued by GET /api/v1/csrf-token");
        return token;
    }

    /** A token whose signed lifetime cannot be trusted, hence treated as expired. */
    private String expiredToken() {
        String token = freshToken();
        int lastDot = token.lastIndexOf('.');
        String signature = token.substring(lastDot + 1);
        String random = token.substring(0, token.substring(0, lastDot).lastIndexOf('.'));
        return random + ".1000000000." + signature;
    }

    /** One rejection counted as {@code csrf-missing}, with its single log entry. */
    private Response missing(Supplier<Response> request) {
        return RejectionObservation.rejectedOnce(registry, 403, RejectionMetrics.CSRF_MISSING,
                request);
    }

    /** One rejection counted as {@code csrf-mismatch}, with its single log entry. */
    private Response mismatch(Supplier<Response> request) {
        return RejectionObservation.rejectedOnce(registry, 403, RejectionMetrics.CSRF_MISMATCH,
                request);
    }

    private Response post(String cookie, String header) {
        return send("POST", DRAFTS, cookie, header);
    }

    private Response send(String method, String path, String cookie, String header) {
        RequestSpecification spec = given().contentType("application/json").body(BODY);
        if (cookie != null) {
            spec = spec.cookie(config.csrf().cookieName(), cookie);
        }
        if (header != null) {
            spec = spec.header(config.csrf().headerName(), header);
        }
        return spec.when().request(method, path);
    }

    private void assertRejected(String label, Response response) {
        assertEquals(403, response.getStatusCode(),
                label + " must be rejected: " + response.getBody().asString());
        assertTrue(String.valueOf(response.getContentType()).contains("problem+json"),
                label + " must be problem+json: " + response.getContentType());
    }

    private String normalisedBody(Response response) {
        assertRejected("rejection", response);
        try {
            ObjectNode body = (ObjectNode) json.readTree(response.getBody().asString());
            body.remove("instance");
            return json.writeValueAsString(body);
        } catch (Exception e) {
            throw new AssertionError("403 body is not parseable problem+json: "
                    + response.getBody().asString(), e);
        }
    }

    private boolean isCsrfCookie(String header) {
        return header.startsWith(config.csrf().cookieName() + "=");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("expected file is missing: " + path, e);
        }
    }
}
