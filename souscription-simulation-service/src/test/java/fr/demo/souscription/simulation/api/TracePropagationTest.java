package fr.demo.souscription.simulation.api;

import fr.demo.souscription.simulation.model.Simulation;
import fr.demo.souscription.simulation.service.SimulationService;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W5-T03: the incoming W3C {@code traceparent} must be continued, never replaced, and the success
 * sampling ratio must never hide a failure.
 *
 * <p>Runs with the test ratio at 0 (see {@code %test.quarkus.otel.traces.sampler.arg}), so the three
 * behaviours are separable in one application instance: a sampled incoming {@code traceparent} is
 * honoured by the parent-based sampler, a successful request without parent is not exported, and an
 * errored request is exported anyway by the error bias. Spans are read back from the in-memory
 * exporter instead of a collector.
 */
@QuarkusTest
class TracePropagationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01";
    private static final String CORRELATION_ID = "corr-5f2c91";

    private static final String NOMINAL = """
            {"initialAmount":1200,"monthlyAmount":100,"durationYears":1,"annualRate":0}
            """;

    @Inject
    InMemorySpanExporter spanExporter;

    @BeforeEach
    void reset() {
        spanExporter.reset();
    }

    @Test
    void incomingTraceparentIsContinued() {
        given().contentType("application/json")
                .header("traceparent", TRACEPARENT)
                .header("X-Correlation-Id", CORRELATION_ID)
                .body(NOMINAL)
                .when().post("/v1/simulations")
                .then().statusCode(200)
                .header("X-Correlation-Id", CORRELATION_ID);

        SpanData serverSpan = awaitServerSpan();
        assertEquals(TRACE_ID, serverSpan.getTraceId(),
                "the caller's trace id must be reused, not replaced by a new trace");
        assertEquals(PARENT_SPAN_ID, serverSpan.getParentSpanId(),
                "the server span must be a child of the caller's span");
        assertTrue(serverSpan.hasEnded(), "the server span must be complete");
        assertFalse(serverSpan.getSpanId().isBlank());
        assertEquals(CORRELATION_ID, correlationAttribute(serverSpan),
                "X-Correlation-Id must be carried as a span attribute");
    }

    @Test
    void successfulRequestWithoutParentIsNotTracedAtRatioZero() {
        given().contentType("application/json").body(NOMINAL)
                .when().post("/v1/simulations")
                .then().statusCode(200);

        assertEquals(Optional.empty(), serverSpan(),
                "at sampling ratio 0 a successful request must not be exported");
    }

    @Test
    void failedRequestIsTracedWhateverTheRatio() {
        QuarkusMock.installMockForType(new FailingSimulationService(), SimulationService.class);

        given().contentType("application/json").body(NOMINAL)
                .when().post("/v1/simulations")
                .then().statusCode(500);

        SpanData serverSpan = awaitServerSpan();
        assertEquals(StatusCode.ERROR, serverSpan.getStatus().getStatusCode(),
                "the exported span must carry the error status");
        assertTrue(serverSpan.hasEnded(), "the server span must be complete");
    }

    private static String correlationAttribute(SpanData span) {
        return span.getAttributes().asMap().entrySet().stream()
                .filter(e -> "correlation.id".equals(e.getKey().getKey()))
                .map(e -> String.valueOf(e.getValue()))
                .findFirst().orElse(null);
    }

    /** The export is asynchronous for sampled spans (batch processor): wait for it to land. */
    private SpanData awaitServerSpan() {
        for (int attempt = 0; attempt < 100; attempt++) {
            Optional<SpanData> span = serverSpan();
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
        throw new AssertionError("no server span was exported");
    }

    private Optional<SpanData> serverSpan() {
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        return spans.stream().filter(s -> s.getKind() == SpanKind.SERVER).findFirst();
    }

    /** Fails the computation so the request ends as a 500, i.e. an errored server span. */
    private static final class FailingSimulationService extends SimulationService {
        @Override
        public Simulation calculate(BigDecimal initial, BigDecimal monthly, int years,
                                    BigDecimal annualRate) {
            throw new IllegalStateException("projection backend unavailable");
        }
    }
}
