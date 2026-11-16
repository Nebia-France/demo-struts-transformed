package fr.demo.souscription.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.util.NoSuchElementException;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DevelopmentTelemetryConfigTest {

    @Test
    void developmentDoesNotRequireATelemetryCollector() throws IOException {
        SmallRyeConfig config = configuration("dev", new Properties());

        assertEquals("http://localhost:4317",
                config.getValue("quarkus.otel.exporter.otlp.endpoint", String.class));
        assertEquals("none", config.getValue("quarkus.otel.traces.exporter", String.class));
        assertEquals(true, config.getValue("quarkus.otel.enabled", Boolean.class));
    }

    @Test
    void developmentCanOptIntoExportingTraces() throws IOException {
        Properties overrides = new Properties();
        overrides.setProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:14317");
        overrides.setProperty("OTEL_TRACES_EXPORTER", "otlp");
        SmallRyeConfig config = configuration("dev", overrides);

        assertEquals("http://localhost:14317",
                config.getValue("quarkus.otel.exporter.otlp.endpoint", String.class));
        assertEquals("otlp", config.getValue("quarkus.otel.traces.exporter", String.class));
    }

    @Test
    void productionStillRequiresAnExplicitTelemetryEndpoint() throws IOException {
        SmallRyeConfig config = configuration("prod", new Properties());

        assertThrows(NoSuchElementException.class,
                () -> config.getValue("quarkus.otel.exporter.otlp.endpoint", String.class));
    }

    private SmallRyeConfig configuration(String profile, Properties overrides) throws IOException {
        Properties properties = new Properties();
        try (InputStream resource = getClass().getResourceAsStream("/application.properties")) {
            properties.load(resource);
        }
        properties.putAll(overrides);
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "application.properties", 100))
                .withProfile(profile)
                .addDefaultInterceptors()
                .build();
    }
}