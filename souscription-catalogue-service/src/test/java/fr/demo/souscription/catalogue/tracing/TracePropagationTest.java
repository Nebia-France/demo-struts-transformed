package fr.demo.souscription.catalogue.tracing;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W5-T03 / NFG-014: the catalogue read must join the caller's trace. An incoming W3C
 * {@code traceparent} has to produce a child server span on the same trace id, never a new
 * trace, otherwise the confirmation-path traces come back incomplete.
 */
@QuarkusTest
class TracePropagationTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String PARENT_SPAN_ID = "b7ad6b7169203331";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01";

    @Inject
    InMemoryExporter exporter;

    @BeforeEach
    void reset() {
        exporter.reset();
    }

    private List<SpanData> serverSpans() {
        for (int i = 0; i < 100 && exporter.finishedSpans().isEmpty(); i++) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return exporter.finishedSpans().stream()
                .filter(s -> s.getKind() == SpanKind.SERVER)
                .collect(Collectors.toList());
    }

    @Test
    void incomingTraceparentProducesExactlyOneChildServerSpan() {
        given().header("traceparent", TRACEPARENT)
                .when().get("/v1/products")
                .then().statusCode(200);

        List<SpanData> spans = serverSpans();
        assertEquals(1, spans.size(), "exactement un span serveur attendu");

        SpanData span = spans.get(0);
        assertEquals(TRACE_ID, span.getTraceId(), "trace orpheline: le traceparent entrant n'a pas ete honore");
        assertEquals(PARENT_SPAN_ID, span.getParentSpanId(), "span parent non repris");
    }

    @Test
    void samplerIsParentBasedSoTheCallerDecisionIsHonoured() {
        String sampler = ConfigProvider.getConfig()
                .getValue("quarkus.otel.traces.sampler", String.class);
        assertEquals("parentbased_traceidratio", sampler);
    }

    @Test
    void serviceNameIsCarriedByTheResourceAttributes() {
        given().header("traceparent", TRACEPARENT)
                .when().get("/v1/products")
                .then().statusCode(200);

        String serviceName = serverSpans().get(0).getResource()
                .getAttribute(io.opentelemetry.api.common.AttributeKey.stringKey("service.name"));
        assertEquals("souscription-catalogue-service", serviceName);
    }

    @Test
    void detailReadIsAlsoAttachedToTheIncomingTrace() {
        given().header("traceparent", TRACEPARENT)
                .when().get("/v1/products/PRUDENT")
                .then().statusCode(200);

        assertTrue(serverSpans().stream().allMatch(s -> TRACE_ID.equals(s.getTraceId())),
                "au moins un span hors de la trace appelante");
    }
}
