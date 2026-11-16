package fr.demo.souscription.simulation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity check for 22-simulation-otel-tracing-json-logs.
 *
 * The decisive assertion is that an inbound traceparent is REUSED. If this service starts a new
 * trace, the confirmation path gateway -> souscription-service -> simulation breaks into fragments,
 * and that 3-level trace is the only replacement for the in-process call stack the monolith gave
 * for free. Worse, independent sampling produces a trace that LOOKS complete and is not.
 *
 * Log capture adapter: a JBoss LogManager handler on the root logger, formatting each record with
 * the same JsonFormatter class Quarkus mounts on the console when quarkus.log.console.json=true
 * (same adapter as the plan-15 and plan-17 checks; the console handler cannot be read back).
 *
 * Sampler: the skeleton requires quarkus.otel.traces.sampler.arg=1, applied here through a
 * class-scoped test profile rather than %test, because TracePropagationTest asserts the error bias
 * at a success ratio of 0 and the two requirements cannot share one value.
 *
 * Span selection: the batch processor exports asynchronously (100 ms in test), so a span of the
 * previous method can land after reset(). Each request therefore carries its own X-Correlation-Id
 * and the span is selected by its correlation.id attribute; the counting assertions wait for the
 * pipeline to settle before resetting.
 */
@QuarkusTest
@TestProfile(TracePropagationParityTest.AlwaysSampledProfile.class)
class TracePropagationParityTest {

    public static final class AlwaysSampledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.otel.traces.sampler.arg", "1");
        }
    }

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01";
    private static final String CORRELATION_ID = "parity-corr-0001";

    private static final String BODY =
            "{\"initialAmount\":1000.00,\"monthlyAmount\":100.00,\"durationYears\":5,\"annualRate\":0.04}";
    private static final String INVALID =
            "{\"initialAmount\":99.99,\"monthlyAmount\":100.00,\"durationYears\":5,\"annualRate\":0.04}";

    private static final List<String> CAPTURED = Collections.synchronizedList(new ArrayList<>());
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    InMemorySpanExporter spanExporter;

    private java.util.logging.Logger root;
    private Handler capturingHandler;

    @BeforeEach
    void reset() throws InterruptedException {
        Thread.sleep(400);
        spanExporter.reset();
        CAPTURED.clear();
        Formatter jsonFormatter = new org.jboss.logmanager.formatters.JsonFormatter();
        root = LogManager.getLogManager().getLogger("");
        capturingHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                CAPTURED.add(jsonFormatter.format(record));
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
        CAPTURED.clear();
    }

    private static List<String> capturedLogLines() {
        synchronized (CAPTURED) {
            return new ArrayList<>(CAPTURED);
        }
    }

    private static int post(String body, boolean withTraceparent) {
        return post(body, withTraceparent, CORRELATION_ID);
    }

    private static int post(String body, boolean withTraceparent, String correlationId) {
        var request = given().contentType("application/json").body(body)
                .header("X-Correlation-Id", correlationId);
        if (withTraceparent) {
            request = request.header("traceparent", TRACEPARENT);
        }
        return request.when().post("/v1/simulations").then().extract().statusCode();
    }

    /** The parity statement: reuse, never regenerate. */
    @Test
    void inboundTraceIdIsReusedAndTheSpanIsLinked() {
        assertEquals(200, post(BODY, true, CORRELATION_ID + "-reuse"));
        SpanData server = awaitServerSpan(CORRELATION_ID + "-reuse");
        assertEquals(TRACE_ID, server.getTraceId(),
                "the inbound trace id must be reused, not regenerated");
        assertEquals(PARENT_SPAN_ID, server.getParentSpanId(),
                "the server span must be a child of the caller's span, otherwise the two never link");
        assertNotEquals(PARENT_SPAN_ID, server.getSpanId());
        assertTrue(server.hasEnded(), "the server span must be complete");
    }

    /** One root span: zero means no correlation at all, two means a duplicated manual span. */
    @Test
    void absentTraceparentStartsExactlyOneTrace() {
        assertEquals(200, post(BODY, false, CORRELATION_ID + "-root"));
        awaitServerSpan(CORRELATION_ID + "-root");
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        List<String> traceIds = spans.stream().map(SpanData::getTraceId).distinct().toList();
        assertEquals(1, traceIds.size(),
                "expected exactly one trace, got " + traceIds.size() + " " + traceIds);
        long roots = spans.stream().filter(s -> !SpanId.isValid(s.getParentSpanId())).count();
        assertEquals(1, roots, "expected exactly one root span, got " + roots);
        long serverSpans = spans.stream().filter(s -> s.getKind() == SpanKind.SERVER).count();
        assertEquals(1, serverSpans,
                "two server spans mean the HTTP layer and application code both create one");
    }

    /** The correlation id is the only identifier a support request can quote. */
    @Test
    void correlationIdReachesTheSpanAndTheLogLine() {
        assertEquals(200, post(BODY, true));
        SpanData server = awaitServerSpan(CORRELATION_ID);
        boolean onSpan = server.getAttributes().asMap().values().stream()
                .anyMatch(value -> CORRELATION_ID.equals(String.valueOf(value)));
        assertTrue(onSpan, "X-Correlation-Id must be a span attribute, and must not be regenerated");
        assertTrue(capturedLogLines().stream().anyMatch(line -> line.contains(CORRELATION_ID)),
                "X-Correlation-Id must appear in the log line");
    }

    /** With a 0.1 ratio and no error bias, nine failures out of ten leave no trace. */
    @Test
    void allErrorResponsesAreTraced() {
        assertEquals(400, post(INVALID, true, CORRELATION_ID + "-error"));
        SpanData server = awaitServerSpan(CORRELATION_ID + "-error");
        assertEquals(StatusCode.ERROR, server.getStatus().getStatusCode(),
                "the error span must carry an error status, otherwise the error bias cannot export it");
    }

    /** Parse-failure rate 0: a single plain-text residue breaks the log pipeline guarantee. */
    @Test
    void everyLogLineIsJson() {
        post(BODY, true);
        post(INVALID, true, CORRELATION_ID + "-error");
        List<String> failures = new ArrayList<>();
        for (String line : capturedLogLines()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                if (!JSON.readTree(trimmed).isObject()) {
                    failures.add(trimmed);
                }
            } catch (Exception e) {
                failures.add(trimmed);
            }
        }
        assertEquals(List.of(), failures, "non-JSON log line(s)");
    }

    /** Spans must not become the exfiltration channel that 15-...-parity closed for logs. */
    @Test
    void noPersonalDataOrCredentialOnTheSpan() {
        post(BODY, true);
        awaitServerSpan(CORRELATION_ID);
        String attributes = spanExporter.getFinishedSpanItems().toString().toLowerCase();
        for (String forbidden : new String[] {"firstname", "lastname", "email", "birthdate",
                "prenom", "authorization", "cookie", "private key"}) {
            assertFalse(attributes.contains(forbidden),
                    "forbidden content [" + forbidden + "] found in span attributes");
        }
    }

    /** The server span of THIS request, identified by its correlation.id attribute. */
    private SpanData awaitServerSpan(String correlationId) {
        return awaitServerSpan(span -> span.getAttributes().asMap().entrySet().stream()
                .anyMatch(e -> "correlation.id".equals(e.getKey().getKey())
                        && correlationId.equals(String.valueOf(e.getValue()))));
    }

    private SpanData awaitServerSpan(Predicate<SpanData> matcher) {
        for (int attempt = 0; attempt < 100; attempt++) {
            Optional<SpanData> span = spanExporter.getFinishedSpanItems().stream()
                    .filter(s -> s.getKind() == SpanKind.SERVER).filter(matcher).findFirst();
            if (span.isPresent()) {
                return span.get();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("no matching server span was exported "
                + "-- is the test sampler ratio set to 1?");
    }
}
