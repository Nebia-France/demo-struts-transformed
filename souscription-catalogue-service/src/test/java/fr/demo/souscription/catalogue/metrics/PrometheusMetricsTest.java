package fr.demo.souscription.catalogue.metrics;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W5-T02: the source exposed no metric. Prometheus series must be served on the management
 * interface only, with a per-endpoint timer and a templatised URI tag (NFG-001, NFG-013).
 */
@QuarkusTest
class PrometheusMetricsTest {

    private static int managementPort() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class)
                .orElse(9001);
    }

    private static String metrics() {
        return given().port(managementPort()).when().get("/q/metrics")
                .then().statusCode(200).extract().body().asString();
    }

    @Test
    void httpServerAndJvmSeriesArePublished() {
        given().when().get("/v1/products").then().statusCode(200);
        String body = metrics();

        assertTrue(body.contains("http_server_requests_seconds"), "serie absente: " + "http_server_requests_seconds");
        assertTrue(body.contains("jvm_memory_used_bytes"), "serie absente: " + "jvm_memory_used_bytes");
        assertTrue(body.contains("jvm_threads_live_threads"), "serie absente: " + "jvm_threads_live_threads");
    }

    @Test
    void perEndpointTimersArePublished() {
        given().when().get("/v1/products").then().statusCode(200);
        given().when().get("/v1/products/PRUDENT").then().statusCode(200);
        String body = metrics();

        assertTrue(body.contains("catalogue_products_list_seconds"), "serie absente: " + "catalogue_products_list_seconds");
        assertTrue(body.contains("catalogue_products_detail_seconds"), "serie absente: " + "catalogue_products_detail_seconds");
    }

    @Test
    void pathParameterStaysTemplatisedInTheUriTag() {
        given().when().get("/v1/products/PRUDENT").then().statusCode(200);
        String body = metrics();

        assertFalse(body.contains("uri=\"/v1/products/PRUDENT\""), "tag haute cardinalite");
        assertTrue(body.contains("uri=\"/v1/products/{code"), "tag uri non templatise");
    }

    @Test
    void metricsAreNotServedOnTheApplicationPort() {
        given().when().get("/q/metrics").then().statusCode(404);
    }
}
