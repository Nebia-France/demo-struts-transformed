package fr.demo.souscription.simulation.service;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * W3-T04 : exactement une ligne de log structurée par projection, sérialisée par le
 * formatter JSON réellement monté sur la console (quarkus.log.console.json=true),
 * portant les figures de la simulation, un horodatage et les identifiants de trace.
 */
@QuarkusTest
class SimulationAuditLogTest {

    private static final String AUDIT_LOGGER = SimulationService.class.getName();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> captured = new ArrayList<>();
    private java.util.logging.Logger root;
    private Handler capturingHandler;

    @BeforeEach
    void attachHandler() {
        assertTrue(ConfigProvider.getConfig().getValue("quarkus.log.console.json", Boolean.class),
                "the console handler must be JSON-formatted");
        // Same formatter class and defaults as the one Quarkus mounts on the console handler
        // (io.quarkus.logging.json.runtime.JsonFormatter extends it); the console handler itself
        // lives in the application log context, which the test JVM cannot read back.
        Formatter jsonFormatter = new org.jboss.logmanager.formatters.JsonFormatter();
        root = LogManager.getLogManager().getLogger("");
        capturingHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (AUDIT_LOGGER.equals(record.getLoggerName())) {
                    captured.add(jsonFormatter.format(record));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        root.addHandler(capturingHandler);
    }

    @AfterEach
    void detachHandler() {
        root.removeHandler(capturingHandler);
    }

    @Test
    void oneValidJsonAuditLinePerRequestWithFiguresAndTraceIds() throws Exception {
        given().contentType(ContentType.JSON)
            .body("{\"initialAmount\":1000.00,\"monthlyAmount\":100.00,\"durationYears\":5,\"annualRate\":0.04}")
        .when().post("/v1/simulations")
        .then().statusCode(200);

        assertEquals(1, captured.size(), "exactly one audit line per request");

        JsonNode line = MAPPER.readTree(captured.get(0));
        JsonNode mdc = line.get("mdc");
        assertNotNull(mdc, "the JSON line must carry the MDC fields");
        assertEquals("1000.00", mdc.path("initialAmount").asText());
        assertEquals("100.00", mdc.path("monthlyAmount").asText());
        assertEquals("5", mdc.path("durationYears").asText());
        assertEquals("0.04", mdc.path("annualRate").asText());
        assertEquals("7000.00", mdc.path("contributed").asText());
        assertTrue(mdc.path("finalCapital").asText().matches("\\d+\\.\\d{2}"),
                "finalCapital must be a plain 2-decimal amount, was " + mdc.path("finalCapital").asText());
        assertTrue(mdc.path("at").asText().endsWith("Z"), "the audit line must carry an instant timestamp");
        assertTrue(line.hasNonNull("timestamp"), "the JSON line must carry the formatter timestamp");
        assertTrue(line.path("message").asText().startsWith("simulation.computed"));

        assertTrue(mdc.path("traceId").asText().matches("[0-9a-f]{32}"),
                "the audit line must carry traceId, was " + mdc.path("traceId").asText());
        assertTrue(mdc.path("spanId").asText().matches("[0-9a-f]{16}"),
                "the audit line must carry spanId, was " + mdc.path("spanId").asText());
    }

    @Test
    void noAuditLineIsEmittedWhenTheGuardRejectsTheInput() {
        given().contentType(ContentType.JSON)
            .body("{\"initialAmount\":10.00,\"monthlyAmount\":100.00,\"durationYears\":5,\"annualRate\":0.04}")
        .when().post("/v1/simulations")
        .then().statusCode(400);

        assertTrue(captured.isEmpty(), "no projection, no audit line");
    }

    @Test
    void everyAuditLineOfSeveralRequestsParsesAsJson() throws Exception {
        for (int i = 0; i < 3; i++) {
            given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":1000.00,\"monthlyAmount\":100.00,\"durationYears\":2,\"annualRate\":0.03}")
            .when().post("/v1/simulations")
            .then().statusCode(200);
        }
        assertEquals(3, captured.size(), "one line per request, never one per monthly iteration");
        for (String json : captured) {
            assertNotNull(MAPPER.readTree(json));
        }
    }
}
