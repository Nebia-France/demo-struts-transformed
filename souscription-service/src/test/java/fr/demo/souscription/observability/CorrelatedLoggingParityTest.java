package fr.demo.souscription.observability;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.persistence.RequiresContainerRuntime;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.WithTestResource;
import fr.demo.souscription.integration.ContractStubsResource;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parity skeleton for 24-souscription-service-json-logs-correlation.
 *
 * Baseline reference: BASELINE.md section 4.8 — the source emitted three unstructured
 * servlet-container log lines and no correlation field. This is a new capability (NFG-014), so the
 * anchor is the documented target; the two PARITY obligations are the surviving SQLState diagnostic
 * and the absence of personal data in logs (the source logged none).
 *
 * SAMPLING PREREQUISITE: the production sampler is a ratio below 1
 * (OTEL_SAMPLING_RATIO:0.1). A correlation assertion under that ratio passes or fails at random and
 * a green run proves nothing, so this test runs under a profile that forces always_on.
 *
 * <p>Adapted to this module's harness: the profile also arms the OpenTelemetry SDK (the global
 * test profile disables it) with no exporter, and the PostgreSQL Dev Services datasource, because
 * {@code /v1/drafts} persists a server-side draft. The container-runtime guard shared by the other
 * integration tests is applied. The capture handler freezes the MDC of each record
 * ({@code copyMdc}), which is resolved lazily otherwise and would be empty at assertion time. No
 * assertion is weakened.
 */
@QuarkusTest
@TestProfile(CorrelatedLoggingParityTest.AlwaysSampleProfile.class)
@RequiresContainerRuntime
@WithTestResource(ContractStubsResource.class)
class CorrelatedLoggingParityTest {

    /** Forces every request to be sampled; the production ratio is left untouched. */
    public static class AlwaysSampleProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("quarkus.otel.enabled", "true"),
                    Map.entry("quarkus.otel.sdk.disabled", "false"),
                    Map.entry("quarkus.otel.traces.exporter", "none"),
                    Map.entry("quarkus.otel.metrics.exporter", "none"),
                    Map.entry("quarkus.otel.logs.exporter", "none"),
                    Map.entry("quarkus.otel.traces.sampler", "always_on"),
                    Map.entry("quarkus.otel.traces.sampler.arg", "1"),
                    Map.entry("quarkus.log.console.json", "true"),
                    Map.entry("quarkus.log.console.json.fields.mdc.enabled", "true"),
                    Map.entry("quarkus.datasource.active", "true"),
                    Map.entry("quarkus.hibernate-orm.active", "true"),
                    Map.entry("quarkus.datasource.devservices.enabled", "true"),
                    Map.entry("quarkus.datasource.devservices.image-name", "postgres:16-alpine"));
        }
    }

    private final List<LogRecord> captured = new CopyOnWriteArrayList<>();
    private Handler handler;

    @BeforeEach
    void attachHandler() {
        captured.clear();
        handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record instanceof ExtLogRecord extended) {
                    extended.copyMdc();
                }
                captured.add(record);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger.getLogger("fr.demo.souscription").addHandler(handler);
    }

    @AfterEach
    void detachHandler() {
        Logger.getLogger("fr.demo.souscription").removeHandler(handler);
    }

    @Test
    void inboundCorrelationIdIsHonouredAndEchoed() {
        String correlationId = "test-corr-1";
        String echoed = given().header("X-Correlation-Id", correlationId)
                .contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
            .when().post("/v1/drafts")
            .then().statusCode(201)
                .extract().header("X-Correlation-Id");

        assertEquals(correlationId, echoed,
                "the gateway logs the value it sent; without the echo the two sides cannot be "
                + "reconciled on the response path");
        assertTrue(mdcValues("correlationId").contains(correlationId),
                "the inbound header must be honoured, not regenerated: regenerating breaks the "
                + "end-to-end trail across the five applications. Seen: " + mdcValues("correlationId"));
    }

    @Test
    void missingCorrelationIdIsGenerated() {
        String echoed = given().contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
            .when().post("/v1/drafts")
            .then().statusCode(201).extract().header("X-Correlation-Id");

        assertNotNull(echoed, "a correlation id must be generated when the header is absent");
        assertTrue(echoed.matches("[0-9a-fA-F-]{36}"), "expected a UUIDv4, got: " + echoed);
    }

    @Test
    @EnabledUnlessPropertyFalse(named = "quarkus.otel.enabled",
            reason = "chemin de retour arriere de la vague 5 : sans l'extension OpenTelemetry le traceId du MDC est vide par construction ; la correlation par correlationId reste verifiee par les autres methodes")
    void everyLogLineCarriesTraceIdAndCorrelationId() {
        given().header("X-Correlation-Id", "test-corr-2")
                .contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
            .when().post("/v1/drafts").then().statusCode(201);

        assertFalse(captured.isEmpty(), "no log line was captured — is the handler attached?");
        assertTrue(mdcValues("traceId").stream().noneMatch(v -> v == null || v.isBlank()),
                "every line must carry a non-empty traceId (requires "
                + "quarkus.log.console.json.fields.mdc.enabled=true and an always_on sampler)");
        assertTrue(mdcValues("correlationId").contains("test-corr-2"));
    }

    @Test
    void problemBodyCarriesTheCorrelationId() {
        String draftId = startDraft();
        String correlationId = UUID.randomUUID().toString();

        given().header("X-Correlation-Id", correlationId)
                .contentType(ContentType.JSON)
                .body("{\"firstName\":\"\",\"lastName\":\"Dupont\","
                        + "\"email\":\"c.d@example.org\",\"birthDate\":\"1990-05-04\"}")
            .when().put("/v1/drafts/" + draftId + "/subscriber")
            .then().statusCode(400)
                .body("correlationId", org.hamcrest.Matchers.is(correlationId));
    }

    @Test
    void noPersonalDataInLogs() {
        String draftId = startDraft();
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Zoe\",\"lastName\":\"Berenger\","
                        + "\"email\":\"zoe.berenger@example.org\",\"birthDate\":\"1990-05-12\"}")
            .when().put("/v1/drafts/" + draftId + "/subscriber").then().statusCode(200);

        for (LogRecord record : captured) {
            String line = String.valueOf(record.getMessage())
                    + String.valueOf(java.util.Arrays.toString(record.getParameters()));
            for (String personal : List.of("zoe.berenger@example.org", "Berenger", "1990-05-12")) {
                assertFalse(line.contains(personal),
                        "CFG-011: the source logged no personal data. A log store usually has a "
                        + "longer retention and a wider audience than the database, so this would "
                        + "defeat the PT20M draft retention. Found '" + personal + "' in: " + line);
            }
        }
    }

    @Test
    void mdcDoesNotLeakBetweenRequests() {
        given().header("X-Correlation-Id", "first-request")
                .contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
            .when().post("/v1/drafts").then().statusCode(201);
        captured.clear();

        given().contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
            .when().post("/v1/drafts").then().statusCode(201);

        assertFalse(mdcValues("correlationId").contains("first-request"),
                "worker threads are pooled: an uncleared MDC attributes one user's log lines to "
                + "another user's correlation id");
    }

    private List<String> mdcValues(String key) {
        // The JSON formatter reads these from the MDC; org.jboss.logmanager exposes them on the
        // record via getMdc(key).
        return captured.stream()
                .map(r -> r instanceof ExtLogRecord ext ? ext.getMdc(key) : null)
                .toList();
    }

    private String startDraft() {
        return given().contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts").then().statusCode(201).extract().path("draftId");
    }
}
