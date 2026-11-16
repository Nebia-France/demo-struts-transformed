package fr.demo.souscription.gateway.observability;

import io.quarkus.logging.LoggingFilter;
import java.util.List;
import java.util.logging.Filter;
import java.util.logging.LogRecord;
import org.jboss.logmanager.ExtLogRecord;

/**
 * Guarantees the three correlation fields on every console record (W6-T03).
 *
 * <p>{@code traceId} and {@code spanId} are written to the MDC by {@code quarkus-opentelemetry}
 * while a span is active, {@code requestId} by {@link RequestCorrelation}. A record written outside
 * a request (startup, shutdown, background task), or while the OpenTelemetry SDK is disabled, has
 * none of them: the missing keys are then added with an empty value, so every JSON record carries
 * the same schema and a log query never has to distinguish "absent" from "empty". The filter never
 * drops a record.
 */
@LoggingFilter(name = CorrelationFieldsFilter.NAME)
public final class CorrelationFieldsFilter implements Filter {

    public static final String NAME = "correlation-fields";

    /** The correlation keys, as they appear in the {@code mdc} object of a JSON record. */
    public static final List<String> KEYS = List.of("traceId", "spanId", RequestCorrelation.REQUEST_ID);

    @Override
    public boolean isLoggable(LogRecord record) {
        if (record instanceof ExtLogRecord ext) {
            for (String key : KEYS) {
                if (ext.getMdc(key) == null) {
                    ext.putMdc(key, "");
                }
            }
        }
        return true;
    }
}
