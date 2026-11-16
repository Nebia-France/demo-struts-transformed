package fr.demo.souscription.gateway.observability;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * In-memory span exporter of the test suite (W6-T04).
 *
 * <p>Being a CDI {@code SpanExporter}, it replaces the OTLP exporter in every {@code @QuarkusTest}:
 * no test ever needs a collector. It only receives spans in {@link TracingTest}, the one test
 * whose profile turns the SDK on; everywhere else {@code %test.quarkus.otel.sdk.disabled=true}.
 */
public class InMemorySpanExporterProducer {

    @Produces
    @Singleton
    InMemorySpanExporter inMemorySpanExporter() {
        return InMemorySpanExporter.create();
    }
}
