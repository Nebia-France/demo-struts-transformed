package fr.demo.souscription.simulation.tracing;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/** In-memory span exporter used by the tracing tests instead of an OTLP collector. */
@ApplicationScoped
public class InMemorySpanExporterProducer {

    @Produces
    @ApplicationScoped
    public InMemorySpanExporter spanExporter() {
        return InMemorySpanExporter.create();
    }
}
