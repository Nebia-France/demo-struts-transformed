package fr.demo.souscription.gateway.observability;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reduces a request path to the route template written in log records (W6-T03).
 *
 * <p>Same rules as {@code quarkus.micrometer.binder.http-server.match-patterns}: evaluated in order,
 * the first match wins, and any path outside the public routes becomes {@code /unmatched}. A product
 * code, a draft id, a confirmation key or an attacker-chosen path therefore never reaches the log.
 */
final class RouteTemplates {

    private static final List<Map.Entry<Pattern, String>> TEMPLATES = List.of(
            Map.entry(Pattern.compile("/api/v1/products/.+"), "/api/v1/products/{code}"),
            Map.entry(Pattern.compile("/api/v1/drafts/[^/]+/subscriber"), "/api/v1/drafts/{id}/subscriber"),
            Map.entry(Pattern.compile("/api/v1/drafts/[^/]+/simulation"), "/api/v1/drafts/{id}/simulation"),
            Map.entry(Pattern.compile("/api/v1/drafts/[^/]+/confirmation"), "/api/v1/drafts/{id}/confirmation"),
            Map.entry(Pattern.compile("/api/v1/drafts/.+"), "/api/v1/drafts/{id}"),
            Map.entry(Pattern.compile("/api/v1/subscriptions/.+"), "/api/v1/subscriptions/{key}"),
            Map.entry(Pattern.compile("/api/v1/products"), "/api/v1/products"),
            Map.entry(Pattern.compile("/api/v1/drafts"), "/api/v1/drafts"),
            Map.entry(Pattern.compile("/api/v1/simulations"), "/api/v1/simulations"),
            Map.entry(Pattern.compile("/api/v1/csrf-token"), "/api/v1/csrf-token"));

    static final String UNMATCHED = "/unmatched";

    private RouteTemplates() {
    }

    static String of(String path) {
        if (path == null) {
            return UNMATCHED;
        }
        for (Map.Entry<Pattern, String> template : TEMPLATES) {
            if (template.getKey().matcher(path).matches()) {
                return template.getValue();
            }
        }
        return UNMATCHED;
    }
}
