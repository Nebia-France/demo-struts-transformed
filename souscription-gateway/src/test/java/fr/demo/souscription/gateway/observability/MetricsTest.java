package fr.demo.souscription.gateway.observability;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import fr.demo.souscription.gateway.edge.CsrfTestPair;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Metrics surface of the gateway (W6-T02).
 *
 * <ul>
 *   <li>each edge rejection (missing or mismatched CSRF token, forbidden method, oversized body)
 *       increments its own {@code gateway_rejections_total} series — the same requests as the
 *       rejection scenarios of {@code features/securite-web};
 *   <li>{@code /q/metrics} is served on the management interface only, never on the public port;
 *   <li>the scrape exposes the server, client, fault tolerance, pool and JVM families the
 *       dashboard and the SLI read;
 *   <li>no label value carries a raw identifier: no UUID, no product code.
 * </ul>
 */
@QuarkusTest
class MetricsTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final List<String> PRODUCT_CODES = List.of("PRUDENT", "EQUILIBRE", "DYNAMIQUE", "INCONNU");

    private static final Pattern LABEL_VALUE = Pattern.compile("=\"([^\"]*)\"");

    @Inject
    MeterRegistry registry;

    @BeforeEach
    void healthyDownstreams() {
        DownstreamWireMocks.CONFIG_KEYS.forEach(DownstreamWireMocks::bringBack);
    }

    @Test
    void missingCsrfTokenIncrementsTheCsrfMissingCounter() {
        double before = rejections(403, RejectionMetrics.CSRF_MISSING);

        given().contentType(ContentType.JSON).body(Map.of("productCode", "PRUDENT"))
                .when().post("/api/v1/drafts")
                .then().statusCode(403);

        assertEquals(before + 1, rejections(403, RejectionMetrics.CSRF_MISSING), 0.0001d);
        assertScrapeHasRejection("403", RejectionMetrics.CSRF_MISSING);
    }

    @Test
    void mismatchedCsrfTokenIncrementsTheCsrfMismatchCounter() {
        String token = given().when().get("/api/v1/csrf-token")
                .then().statusCode(200).extract().jsonPath().getString("token");
        double before = rejections(403, RejectionMetrics.CSRF_MISMATCH);

        given().cookie(config("gateway.csrf.cookie-name"), token)
                .header(config("gateway.csrf.header-name"), token + "x")
                .contentType(ContentType.JSON).body(Map.of("productCode", "PRUDENT"))
                .when().post("/api/v1/drafts")
                .then().statusCode(403);

        assertEquals(before + 1, rejections(403, RejectionMetrics.CSRF_MISMATCH), 0.0001d);
        assertScrapeHasRejection("403", RejectionMetrics.CSRF_MISMATCH);
    }

    @Test
    void forbiddenMethodIncrementsTheMethodCounter() {
        double before = rejections(405, RejectionMetrics.METHOD);

        given().when().delete("/api/v1/products")
                .then().statusCode(405);

        assertEquals(before + 1, rejections(405, RejectionMetrics.METHOD), 0.0001d);
        assertScrapeHasRejection("405", RejectionMetrics.METHOD);
    }

    @Test
    void oversizedBodyIncrementsTheSizeCounter() {
        double before = rejections(413, RejectionMetrics.SIZE);
        String oversized = "{\"productCode\":\"" + "A".repeat(65 * 1024) + "\"}";

        CsrfTestPair.withCsrf().contentType(ContentType.JSON).body(oversized)
                .when().post("/api/v1/drafts")
                .then().statusCode(413);

        assertEquals(before + 1, rejections(413, RejectionMetrics.SIZE), 0.0001d);
        assertScrapeHasRejection("413", RejectionMetrics.SIZE);
    }

    @Test
    void metricsAreServedOnTheManagementPortOnly() {
        given().port(managementPort()).when().get("/q/metrics").then().statusCode(200);
        given().when().get("/q/metrics").then().statusCode(404);
    }

    @Test
    void scrapeExposesTheFamiliesReadByTheDashboardAndTheSli() {
        generateTraffic();

        String scrape = scrape();

        for (String family : List.of("http_server_requests_seconds_count", "http_client_requests_seconds_count",
                "http_server_requests_seconds_bucket", "http_client_requests_seconds_bucket",
                "ft_circuitbreaker_state", "http_client_pending_requests", "jvm_memory_used_bytes",
                "gateway_rejections_total")) {
            assertTrue(scrape.lines().anyMatch(line -> line.startsWith(family)),
                    family + " doit etre expose sur /q/metrics");
        }
    }

    @Test
    void noLabelValueCarriesARawIdentifier() {
        generateTraffic();
        // Rejected before any route matched: the raw path must not become a label either.
        given().when().delete("/api/v1/drafts/" + UUID.randomUUID()).then().statusCode(405);
        given().when().patch("/" + UUID.randomUUID() + "/" + "PRUDENT").then().statusCode(405);
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/api/v1/drafts/" + UUID.randomUUID() + "/confirmation")
                .then().statusCode(403);

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
        assertTrue(scrape.contains("uri=\"/api/v1/products/{code}\""),
                "la route detail produit doit etre etiquetee par son gabarit");
        assertTrue(scrape.contains("uri=\"/api/v1/drafts/{id}\""),
                "la route brouillon doit etre etiquetee par son gabarit");
        assertTrue(scrape.contains("uri=\"/api/v1/drafts/{id}/confirmation\""),
                "une sous-ressource du brouillon garde son propre gabarit");
        assertTrue(scrape.contains("uri=\"/unmatched\""),
                "un chemin hors des routes publiques est regroupe sous /unmatched");
    }

    /** One request per public route family, with real identifiers, plus one downstream 404. */
    private static void generateTraffic() {
        given().when().get("/api/v1/products").then().statusCode(200);
        given().when().get("/api/v1/products/PRUDENT").then().statusCode(200);
        given().when().get("/api/v1/products/INCONNU").then().statusCode(404);
        given().when().get("/api/v1/drafts/11111111-1111-1111-1111-111111111111").then().statusCode(200);
        given().when().get("/api/v1/subscriptions/" + UUID.randomUUID());
    }

    private double rejections(int status, String reason) {
        Counter counter = registry.find(RejectionMetrics.METRIC)
                .tag("status", String.valueOf(status)).tag("reason", reason).counter();
        assertTrue(counter != null, "la serie " + status + "/" + reason + " doit etre pre-enregistree");
        return counter.count();
    }

    private static void assertScrapeHasRejection(String status, String reason) {
        assertTrue(scrape().lines().anyMatch(line -> line.startsWith("gateway_rejections_total{")
                        && line.contains("reason=\"" + reason + "\"")
                        && line.contains("status=\"" + status + "\"")),
                "gateway_rejections_total{status=" + status + ",reason=" + reason + "} doit etre expose");
    }

    private static String scrape() {
        return given().port(managementPort()).when().get("/q/metrics")
                .then().statusCode(200).extract().asString();
    }

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }

    private static int managementPort() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class)
                .orElse(9001);
    }
}
