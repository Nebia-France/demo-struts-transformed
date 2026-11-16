package fr.demo.souscription.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logmanager.MDC;

/**
 * Correlation des journaux de souscription-service (W5-T01, ecart NFG-014).
 *
 * <p>Pose dans le MDC les trois champs qui rendent une trajectoire utilisateur reconstituable
 * dans la topologie cible : {@code correlationId}, propage par {@code souscription-gateway} dans
 * l'en-tete {@code X-Correlation-Id} des contrats geles, ainsi que {@code traceId} et
 * {@code spanId} du contexte OpenTelemetry courant. La sortie console JSON serialise le MDC :
 * chaque ligne de journal porte donc les trois champs.
 *
 * <p>En l'absence d'en-tete (appel direct au service, sonde), un UUIDv4 est genere : il n'existe
 * aucune requete sans identifiant de correlation. La valeur recue est validee avant d'etre
 * journalisee ou renvoyee : un en-tete arbitraire ne peut pas injecter de contenu dans une ligne
 * de journal ni dans un en-tete de reponse.
 *
 * <p>L'identifiant retenu est renvoye dans l'en-tete {@code X-Correlation-Id} de la reponse, ce
 * qui permet au client de citer la valeur a suivre dans les journaux, et il alimente le champ
 * {@code correlationId} du corps {@code application/problem+json}. Le MDC est vide a la sortie du
 * filtre de reponse : aucune valeur ne fuit vers la requete suivante servie par le meme thread.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class CorrelationFilter implements ContainerRequestFilter, ContainerResponseFilter {

    /** En-tete de correlation des contrats geles, propage par la passerelle. */
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    /** Cle MDC de l'identifiant de correlation, serialisee dans chaque ligne JSON. */
    public static final String CORRELATION_ID = "correlationId";

    /** Cle MDC de l'identifiant de trace, alimentee par l'extension OpenTelemetry. */
    public static final String TRACE_ID = "traceId";

    /** Cle MDC de l'identifiant de span. */
    public static final String SPAN_ID = "spanId";

    /**
     * Forme acceptee d'un identifiant de correlation entrant : jetons alphanumeriques, tiret et
     * souligne, 64 caracteres au plus. Tout autre contenu (retour chariot, separateur JSON,
     * sequence de controle) est remplace par un identifiant genere.
     */
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String correlationId = resolve(requestContext.getHeaderString(CORRELATION_HEADER));
        requestContext.setProperty(CORRELATION_ID, correlationId);
        MDC.put(CORRELATION_ID, correlationId);

        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            MDC.put(TRACE_ID, spanContext.getTraceId());
            MDC.put(SPAN_ID, spanContext.getSpanId());
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        Object correlationId = requestContext.getProperty(CORRELATION_ID);
        if (correlationId != null) {
            responseContext.getHeaders().putSingle(CORRELATION_HEADER, correlationId);
        }
        MDC.remove(CORRELATION_ID);
        MDC.remove(TRACE_ID);
        MDC.remove(SPAN_ID);
    }

    /** Identifiant de correlation courant, ou {@code null} hors d'une requete filtree. */
    public static String currentCorrelationId() {
        Object value = MDC.get(CORRELATION_ID);
        return value == null ? null : value.toString();
    }

    private static String resolve(String header) {
        if (header != null && ACCEPTED.matcher(header).matches()) {
            return header;
        }
        return UUID.randomUUID().toString();
    }
}
