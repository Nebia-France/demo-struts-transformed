package fr.demo.souscription.gateway.observability;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import fr.demo.souscription.gateway.edge.CsrfTestPair;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parity check for 26-souscription-gateway-metrics-slo.
 *
 * <p>The source exposed no metric at all: this is a new-capability validation. The two properties
 * that matter are:
 * <ul>
 *   <li>every edge rejection is counted <em>exactly once</em>, in its own
 *       {@code gateway_rejections_total{status, reason}} series and in no other — an uncounted
 *       rejection is invisible to the SLI, a double count corrupts it;
 *   <li>cardinality stays bounded: no product code, draft id, confirmation key or CSRF token ever
 *       becomes a label value, and {@code /q/metrics} is served on the management interface only.
 * </ul>
 *
 * <p>The rejection requests are the gateway translation of the rejection scenarios of
 * {@code features/securite-web} (empty, wrong and case-changed token, absent token, 101-character
 * token, forbidden methods), plus the 64 KiB body limit. The suite binds random ports
 * ({@code quarkus.http.test-port=0}, {@code quarkus.management.test-port=0}); the 9000-vs-8080
 * split is asserted on the packaged {@code application.properties} and observed on the effective
 * ports.
 */
@QuarkusTest
class MetricsParityTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final List<String> PRODUCT_CODES = List.of("PRUDENT", "EQUILIBRE", "DYNAMIQUE", "INCONNU");

    private static final Pattern LABEL_VALUE = Pattern.compile("=\"([^\"]*)\"");

    private static final String DRAFT_ID = "11111111-1111-1111-1111-111111111111";

    private static final String SUBSCRIPTION_KEY = "33333333-3333-3333-3333-333333333333";

    @Inject
    MeterRegistry registry;

    @BeforeEach
    void healthyDownstreams() {
        DownstreamWireMocks.CONFIG_KEYS.forEach(DownstreamWireMocks::bringBack);
    }

    // --- Step 1 / Step 8: each rejection increments its own counter exactly once -----------------

    @Test
    void emptyCsrfTokenCountsAsCsrfMissing() {
        String token = freshToken();
        assertCountedOnce(403, RejectionMetrics.CSRF_MISSING,
                () -> postDraft(given().cookie(cookieName(), token).header(headerName(), "")));
    }

    @Test
    void absentCsrfTokenCountsAsCsrfMissing() {
        String token = freshToken();
        assertCountedOnce(403, RejectionMetrics.CSRF_MISSING,
                () -> postDraft(given().cookie(cookieName(), token)));
    }

    @Test
    void wrongCsrfTokenCountsAsCsrfMismatch() {
        String token = freshToken();
        assertCountedOnce(403, RejectionMetrics.CSRF_MISMATCH,
                () -> postDraft(given().cookie(cookieName(), token).header(headerName(), "jeton-invalide")));
    }

    @Test
    void caseChangedCsrfTokenCountsAsCsrfMismatch() {
        String token = freshToken();
        String flipped = token.equals(token.toUpperCase()) ? token.toLowerCase() : token.toUpperCase();
        assertCountedOnce(403, RejectionMetrics.CSRF_MISMATCH,
                () -> postDraft(given().cookie(cookieName(), token).header(headerName(), flipped)));
    }

    @Test
    void overlongCsrfTokenCountsAsCsrfMismatch() {
        int max = ConfigProvider.getConfig().getValue("gateway.csrf.max-token-length", Integer.class);
        String overlong = "x".repeat(max + 1);
        assertCountedOnce(403, RejectionMetrics.CSRF_MISMATCH,
                () -> postDraft(given().cookie(cookieName(), overlong).header(headerName(), overlong)));
    }

    @Test
    void everyForbiddenMethodCountsAsMethod() {
        for (String method : List.of("DELETE", "PATCH", "OPTIONS", "TRACE")) {
            assertCountedOnce(405, RejectionMetrics.METHOD,
                    () -> given().when().request(method, "/api/v1/products").then().statusCode(405));
        }
    }

    @Test
    void oversizedBodyCountsAsSize() {
        String oversized = "{\"productCode\":\"" + "A".repeat(65 * 1024) + "\"}";
        assertCountedOnce(413, RejectionMetrics.SIZE,
                () -> CsrfTestPair.withCsrf().contentType(ContentType.JSON).body(oversized)
                        .when().post("/api/v1/drafts").then().statusCode(413));
    }

    @Test
    void acceptedRequestsIncrementNoRejectionCounter() {
        Map<String, Double> before = rejectionSnapshot();

        CsrfTestPair.withCsrf().contentType(ContentType.JSON).body(Map.of("productCode", "PRUDENT"))
                .when().post("/api/v1/drafts").then().statusCode(201);
        given().when().get("/api/v1/products").then().statusCode(200);
        given().when().head("/api/v1/products").then().statusCode(200);

        assertEquals(before, rejectionSnapshot(), "une requete acceptee ne doit incrementer aucun rejet");
    }

    @Test
    void reasonsAreAClosedSetAndUnknownPairsAreRefused() {
        assertEquals(Map.of(RejectionMetrics.CSRF_MISSING, 403, RejectionMetrics.CSRF_MISMATCH, 403,
                RejectionMetrics.METHOD, 405, RejectionMetrics.SIZE, 413), RejectionMetrics.KNOWN);
        String scrape = scrape();
        RejectionMetrics.KNOWN.forEach((reason, status) -> assertTrue(scrape.lines().anyMatch(line ->
                        line.startsWith("gateway_rejections_total{")
                                && line.contains("reason=\"" + reason + "\"")
                                && line.contains("status=\"" + status + "\"")),
                "serie pre-enregistree absente : " + status + "/" + reason));
    }

    // --- Step 2: required families ----------------------------------------------------------------

    @Test
    void requiredMetricFamiliesAreExposed() {
        generateTraffic();
        String scrape = scrape();
        for (String family : List.of("http_server_requests_seconds", "http_client_requests_seconds",
                "ft_circuitbreaker_state", "http_client_pending_requests", "jvm_memory_used_bytes")) {
            assertTrue(scrape.lines().anyMatch(line -> line.startsWith(family)),
                    family + " doit etre expose sur /q/metrics");
        }
        for (String edge : List.of("catalogue", "simulation", "subscription")) {
            assertTrue(scrape.lines().anyMatch(line -> line.startsWith("http_client_pending_requests{")
                            && line.contains("\"" + edge + "\"")),
                    "http_client_pending_requests doit etre publie pour l'arete " + edge);
        }
    }

    // --- Step 3: /q/metrics on the management interface only --------------------------------------

    @Test
    void metricsAreServedOnManagementOnly() {
        given().port(managementPort()).when().get("/q/metrics").then().statusCode(200);
        given().when().get("/q/metrics").then().statusCode(404);

        Properties packaged = packagedProperties();
        assertEquals("true", packaged.getProperty("quarkus.management.enabled"));
        assertEquals("9000", packaged.getProperty("quarkus.management.port"));
        assertEquals("8080", packaged.getProperty("quarkus.http.port"));
    }

    // --- Step 4: templated URIs, no identifier in any label value ---------------------------------

    @Test
    void noIdentifierInAnyLabelValue() {
        List<String> csrfTokens = new ArrayList<>();
        generateTraffic();
        String token = freshToken();
        csrfTokens.add(token);
        postDraft(given().cookie(cookieName(), token).header(headerName(), token + "x"));
        given().when().delete("/api/v1/drafts/" + UUID.randomUUID()).then().statusCode(405);
        given().when().patch("/" + UUID.randomUUID() + "/PRUDENT").then().statusCode(405);
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/api/v1/drafts/" + UUID.randomUUID() + "/confirmation").then().statusCode(403);

        String scrape = scrape();

        scrape.lines().filter(line -> !line.startsWith("#")).forEach(line -> {
            Matcher labels = LABEL_VALUE.matcher(line);
            while (labels.find()) {
                String value = labels.group(1);
                assertFalse(UUID_PATTERN.matcher(value).find(), "identifiant brut en etiquette : " + line);
                for (String code : PRODUCT_CODES) {
                    assertFalse(value.contains(code), "code produit brut en etiquette : " + line);
                }
            }
        });
        for (String csrf : csrfTokens) {
            assertFalse(scrape.contains(csrf), "un jeton CSRF ne doit jamais apparaitre dans le scrape");
        }
        for (String template : List.of("/api/v1/products/{code}", "/api/v1/drafts/{id}",
                "/api/v1/subscriptions/{key}")) {
            assertTrue(scrape.contains("uri=\"" + template + "\""), "gabarit attendu : " + template);
        }

        String patterns = packagedProperties().getProperty("quarkus.micrometer.binder.http-server.match-patterns");
        assertNotNull(patterns, "match-patterns doit etre configure");
        for (String template : List.of("/api/v1/products/{code}", "/api/v1/drafts/{id}",
                "/api/v1/subscriptions/{key}", "/unmatched")) {
            assertTrue(patterns.contains("=" + template), "motif absent : " + template);
        }
    }

    // --- Step 5: mTLS certificate expiry gauge -----------------------------------------------------

    @Test
    void certificateExpiryGaugeIsRegisteredFromTheConfiguredStores() throws Exception {
        String password = "test-only-parity-password";
        Path dir = Files.createDirectories(Path.of("target", "expiry-parity"));
        Path store = dir.resolve("client-" + System.nanoTime() + ".p12");
        long before = Instant.now().getEpochSecond();
        Process keytool = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=souscription-gateway", "-validity", "45",
                "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", password, "-keypass", password))
                .inheritIO().start();
        assertEquals(0, keytool.waitFor(), "keytool doit generer le magasin de test");

        Map<String, String> values = new HashMap<>();
        for (String client : CertificateExpiryMetrics.CLIENTS) {
            String prefix = "quarkus.rest-client." + client + ".";
            values.put(prefix + "key-store", "file:" + store.toAbsolutePath());
            values.put(prefix + "key-store-password", password);
            values.put(prefix + "trust-store", "file:" + store.toAbsolutePath());
            values.put(prefix + "trust-store-password", password);
        }
        CertificateExpiryMetrics metrics = new CertificateExpiryMetrics();
        metrics.registry = new SimpleMeterRegistry();
        metrics.config = new SmallRyeConfigBuilder().withDefaultValues(values).build();

        metrics.onStart(null);

        long expected = before + 45L * 24 * 3600;
        for (String client : CertificateExpiryMetrics.CLIENTS) {
            for (String storeTag : List.of("keystore", "truststore")) {
                Gauge gauge = metrics.registry.find(CertificateExpiryMetrics.METRIC)
                        .tag("client", client).tag("store", storeTag).gauge();
                assertNotNull(gauge, "jauge d'expiration absente pour " + client + "/" + storeTag);
                assertTrue(Math.abs(gauge.value() - expected) < 120,
                        "la jauge doit porter le notAfter : " + gauge.value());
            }
        }

        Properties packaged = packagedProperties();
        for (String client : CertificateExpiryMetrics.CLIENTS) {
            assertNotNull(packaged.getProperty("%prod.quarkus.rest-client." + client + ".key-store"),
                    "le magasin prod de l'arete " + client + " doit etre configure");
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void assertCountedOnce(int status, String reason, Runnable rejection) {
        Map<String, Double> before = rejectionSnapshot();
        rejection.run();
        Map<String, Double> after = rejectionSnapshot();
        String key = status + "/" + reason;
        RejectionMetrics.KNOWN.forEach((r, s) -> {
            String series = s + "/" + r;
            double expectedDelta = series.equals(key) ? 1d : 0d;
            assertEquals(expectedDelta, after.get(series) - before.get(series), 0.0001d,
                    "variation inattendue de " + series + " pour le rejet " + key);
        });
        String scrape = scrape();
        assertTrue(scrape.lines().anyMatch(line -> line.startsWith("gateway_rejections_total{")
                        && line.contains("reason=\"" + reason + "\"")
                        && line.contains("status=\"" + status + "\"")),
                "gateway_rejections_total{status=" + status + ",reason=" + reason + "} doit etre expose");
    }

    private Map<String, Double> rejectionSnapshot() {
        Map<String, Double> snapshot = new HashMap<>();
        RejectionMetrics.KNOWN.forEach((reason, status) -> {
            Counter counter = registry.find(RejectionMetrics.METRIC)
                    .tag("status", String.valueOf(status)).tag("reason", reason).counter();
            assertNotNull(counter, "serie " + status + "/" + reason + " non enregistree");
            snapshot.put(status + "/" + reason, counter.count());
        });
        return snapshot;
    }

    private static void postDraft(RequestSpecification request) {
        request.contentType(ContentType.JSON).body(Map.of("productCode", "PRUDENT"))
                .when().post("/api/v1/drafts").then().statusCode(403);
    }

    private static void generateTraffic() {
        given().when().get("/api/v1/products").then().statusCode(200);
        given().when().get("/api/v1/products/PRUDENT").then().statusCode(200);
        given().when().get("/api/v1/products/INCONNU").then().statusCode(404);
        given().when().get("/api/v1/drafts/" + DRAFT_ID).then().statusCode(200);
        given().when().get("/api/v1/subscriptions/" + SUBSCRIPTION_KEY).then().statusCode(200);
        CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                .body(Map.of("productCode", "PRUDENT", "initialAmount", 1000, "monthlyAmount", 100,
                        "durationYears", 10))
                .when().post("/api/v1/simulations");
    }

    private static String freshToken() {
        return given().when().get("/api/v1/csrf-token")
                .then().statusCode(200).extract().jsonPath().getString("token");
    }

    private static String scrape() {
        return given().port(managementPort()).when().get("/q/metrics")
                .then().statusCode(200).extract().asString();
    }

    private static String cookieName() {
        return ConfigProvider.getConfig().getValue("gateway.csrf.cookie-name", String.class);
    }

    private static String headerName() {
        return ConfigProvider.getConfig().getValue("gateway.csrf.header-name", String.class);
    }

    private static int managementPort() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class)
                .orElse(9001);
    }

    private static Properties packagedProperties() {
        Properties properties = new Properties();
        try (InputStream in = MetricsParityTest.class.getResourceAsStream("/application.properties")) {
            assertNotNull(in, "application.properties doit etre package");
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }
}
