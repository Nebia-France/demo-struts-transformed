package fr.demo.souscription.gateway.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;

/**
 * Business journey attribute of the gateway spans (W6-T04).
 *
 * <p>The value is taken from a closed set and never from the request: it is the only attribute the
 * gateway adds to a span (see the attribute allow-list of {@code docs/observability.md}).
 */
public final class Journeys {

    /** Attribute carrying the journey of the current span. */
    public static final AttributeKey<String> JOURNEY = AttributeKey.stringKey("gateway.journey");

    public static final String CATALOGUE = "catalogue";

    public static final String SIMULATION = "simulation";

    public static final String CONFIRM = "confirm";

    private Journeys() {
    }

    /** Tags the current span with one of the journey constants of this class. */
    public static void tag(String journey) {
        Span.current().setAttribute(JOURNEY, journey);
    }
}
