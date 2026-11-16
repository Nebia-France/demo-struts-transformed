package fr.demo.souscription.gateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Counts the requests the edge filters reject before any route runs, exported as
 * {@code gateway_rejections_total{status, reason}} (W6-T02).
 *
 * <p>Both labels come from a closed set declared here; a caller can never put a request-derived
 * value (path, token, identifier) in a label, which keeps the cardinality bounded. Every known
 * series is registered at startup with the value 0, so an alert on its rate never reads an absent
 * series as "no rejection".
 */
@Startup
@ApplicationScoped
public class RejectionMetrics {

    private static final Logger LOG = Logger.getLogger(RejectionMetrics.class);

    static final String METRIC = "gateway.rejections";

    public static final String CSRF_MISSING = "csrf-missing";
    public static final String CSRF_MISMATCH = "csrf-mismatch";
    public static final String METHOD = "method";
    public static final String SIZE = "size";

    /** Closed set of (reason, status) pairs; 429 is absent because no rate limiting is enabled. */
    static final Map<String, Integer> KNOWN = Map.of(
            CSRF_MISSING, 403,
            CSRF_MISMATCH, 403,
            METHOD, 405,
            SIZE, 413);

    @Inject
    MeterRegistry registry;

    @PostConstruct
    void registerKnownSeries() {
        KNOWN.forEach((reason, status) -> counter(status, reason));
    }

    /**
     * Counts one rejection and writes its single log record (W6-T03): the counter and the journal
     * are fed from this one place, so the number of {@code rejection} records always equals the
     * counter value. The record carries the closed-set reason and the templated route only, never
     * the raw path, a token value or any request content.
     *
     * @param rawPath the normalised request path, reduced to its template before being logged
     */
    public void rejected(int status, String reason, String rawPath) {
        Integer expected = KNOWN.get(reason);
        if (expected == null || expected != status) {
            throw new IllegalArgumentException("Unknown rejection " + status + "/" + reason);
        }
        counter(status, reason).increment();
        LOG.warnf("%s rejection reason=%s status=%d route=%s", kind(reason), reason, status,
                RouteTemplates.of(rawPath));
    }

    private static String kind(String reason) {
        return reason.startsWith("csrf") ? "csrf" : reason;
    }

    private Counter counter(int status, String reason) {
        return Counter.builder(METRIC)
                .description("Requests rejected by the gateway edge filters")
                .tag("status", String.valueOf(status))
                .tag("reason", reason)
                .register(registry);
    }
}
