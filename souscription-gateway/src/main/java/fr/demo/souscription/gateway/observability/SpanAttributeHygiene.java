package fr.demo.souscription.gateway.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Singleton;

/**
 * Keeps the gateway server spans free of identifiers and personal data (W6-T04).
 *
 * <p>Applied when a server span starts, before any processor or exporter reads it:
 * <ul>
 *   <li>{@code url.path} and {@code http.route} become the route template of {@link RouteTemplates}
 *       (same rule as the metrics and the log records): no draft id, product code or
 *       attacker-chosen path is kept, and the span is named {@code METHOD template};</li>
 *   <li>{@code client.address}, the end user's IP address forwarded by the ingress, is replaced
 *       by {@link #REDACTED}.</li>
 * </ul>
 * Exception events of the REST layer, whose messages may quote the rejected input, are not
 * recorded at all ({@code quarkus.otel.instrument.rest=false}, see docs/observability.md).
 */
@Singleton
@Unremovable
public class SpanAttributeHygiene implements SpanProcessor {

    static final String REDACTED = "redacted";

    private static final AttributeKey<String> URL_PATH = AttributeKey.stringKey("url.path");

    private static final AttributeKey<String> HTTP_ROUTE = AttributeKey.stringKey("http.route");

    private static final AttributeKey<String> HTTP_METHOD = AttributeKey.stringKey("http.request.method");

    private static final AttributeKey<String> CLIENT_ADDRESS = AttributeKey.stringKey("client.address");

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        if (span.getKind() != SpanKind.SERVER) {
            return;
        }
        String path = span.getAttribute(URL_PATH);
        if (path != null) {
            String template = RouteTemplates.of(path);
            span.setAttribute(URL_PATH, template);
            span.setAttribute(HTTP_ROUTE, template);
            String method = span.getAttribute(HTTP_METHOD);
            span.updateName(method == null ? template : method + " " + template);
        }
        if (span.getAttribute(CLIENT_ADDRESS) != null) {
            span.setAttribute(CLIENT_ADDRESS, REDACTED);
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }
}
