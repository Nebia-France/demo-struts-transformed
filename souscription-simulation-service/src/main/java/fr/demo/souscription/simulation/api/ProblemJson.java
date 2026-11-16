package fr.demo.souscription.simulation.api;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.util.List;

/** Construction des réponses application/problem+json (aucun détail interne exposé, NFG-008). */
final class ProblemJson {

    static final String PROBLEM_JSON = "application/problem+json";
    private static final String CORRELATION_ID = "X-Correlation-Id";

    private ProblemJson() {
    }

    static Response response(int status, String title, String detail,
                             List<FieldErrors.FieldError> errors, HttpHeaders headers) {
        // Toute réponse d'erreur marque le span courant en erreur : le processeur à biais d'erreur
        // (ErrorBiasedSpanProcessor) n'exporte que les spans terminés en ERROR, et sans ce marquage
        // un 4xx resterait invisible dans le collecteur au ratio de succès de 0,1. Le titre est une
        // constante du mapper, jamais une donnée de la requête.
        Span.current().setStatus(StatusCode.ERROR, title);
        return Response.status(status)
                .type(PROBLEM_JSON)
                .entity(new FieldErrors("about:blank", title, status, detail,
                        correlationId(headers), errors))
                .build();
    }

    private static String correlationId(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String value = headers.getHeaderString(CORRELATION_ID);
        return value == null || value.isBlank() ? null : value;
    }
}
