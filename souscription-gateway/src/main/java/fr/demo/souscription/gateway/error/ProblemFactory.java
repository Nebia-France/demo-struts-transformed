package fr.demo.souscription.gateway.error;

import fr.demo.souscription.gateway.api.dto.FieldErrors;
import fr.demo.souscription.gateway.client.DownstreamHttpException;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;

/**
 * Maps an exception class to the frozen public status of the gateway (W2-T03).
 *
 * <p>The statuses formerly declared as container error pages (400, 403, 404, 405, 409, 500 and the
 * {@code java.lang.Throwable} catch-all) keep exactly their source meaning. The downstream failure
 * classes absent from the monolith are added here and frozen for plans 20 and 21:
 * relayable downstream 4xx keep their status, downstream 5xx become 502, a timeout becomes 504 and
 * an open circuit becomes 503. See {@code docs/error-mapping.md}.
 */
public final class ProblemFactory {

    /**
     * Base URI of the stable, dereferenceable problem types, read from
     * {@code problem.type-base} so no absolute URI is hardcoded in the sources. The
     * fallback is the deployment-independent URN form used when the property is absent.
     */
    public static final String TYPE_BASE = org.eclipse.microprofile.config.ConfigProvider.getConfig()
            .getOptionalValue("problem.type-base", String.class)
            .orElse("urn:horizon-souscription:problems:");

    /** Seconds advertised by {@code Retry-After} when the circuit breaker is open. */
    public static final int RETRY_AFTER_SECONDS = 30;

    /** Downstream 4xx statuses whose meaning is public and therefore relayed unchanged. */
    private static final Set<Integer> RELAYABLE_DOWNSTREAM_4XX = Set.of(400, 404, 409, 422);

    private static final Map<Integer, String> TITLES = Map.ofEntries(
            Map.entry(400, "Requete invalide"),
            Map.entry(403, "Jeton de securite invalide"),
            Map.entry(404, "Ressource introuvable"),
            Map.entry(405, "Methode HTTP non autorisee"),
            Map.entry(409, "Conflit avec l'etat courant"),
            Map.entry(413, "Corps de requete trop volumineux"),
            Map.entry(415, "Type de media non supporte"),
            Map.entry(422, "Requete non traitable"),
            Map.entry(500, "Erreur interne"),
            Map.entry(502, "Service aval en erreur"),
            Map.entry(503, "Service momentanement indisponible"),
            Map.entry(504, "Delai d'attente depasse"));

    private static final Map<Integer, String> SLUGS = Map.ofEntries(
            Map.entry(400, "bad-request"),
            Map.entry(403, "forbidden"),
            Map.entry(404, "not-found"),
            Map.entry(405, "method-not-allowed"),
            Map.entry(409, "conflict"),
            Map.entry(413, "payload-too-large"),
            Map.entry(415, "unsupported-media-type"),
            Map.entry(422, "unprocessable-entity"),
            Map.entry(500, "internal-error"),
            Map.entry(502, "downstream-error"),
            Map.entry(503, "service-unavailable"),
            Map.entry(504, "downstream-timeout"));

    /**
     * Statuses whose {@code detail} carries a recovery instruction instead of repeating the title.
     * The 504 of the confirmation edge is the only one: no automatic retry is performed, so the
     * caller is told to re-submit the <em>same</em> confirmation key, which the provider absorbs
     * through the unique {@code confirmation_key} constraint (W4-T02).
     */
    private static final Map<Integer, String> DETAILS = Map.of(504,
            "Delai d'attente depasse en aval ; aucune nouvelle tentative n'est effectuee "
                    + "automatiquement : resoumettre la demande avec la meme cle de confirmation "
                    + "(Idempotency-Key), aucune souscription ne sera enregistree en double.");

    private ProblemFactory() {
    }
    /** Problem for an exception, with no correlation id available. */
    public static Problem of(Throwable t) {
        return of(t, null);
    }

    /**
     * Problem for an exception.
     *
     * @param instance the request id used for correlation, or {@code null}
     */
    public static Problem of(Throwable t, String instance) {
        Throwable cause = unwrap(t);
        int status = statusOf(cause);
        List<FieldErrors.FieldError> errors = relayedOrLocalErrors(cause);
        return forStatus(status, instance, errors);
    }

    /**
     * The {@code errors} member: the local validation violations for a request rejected at the
     * edge, or the field-level errors a downstream validating service produced, relayed verbatim.
     * Those relayed errors are the only part of a downstream body allowed on the public surface
     * (W4-T05).
     */
    private static List<FieldErrors.FieldError> relayedOrLocalErrors(Throwable cause) {
        if (cause instanceof ConstraintViolationException violation) {
            return fieldErrors(violation);
        }
        if (cause instanceof DownstreamHttpException downstream) {
            return downstream.fieldErrors();
        }
        return null;
    }

    /** Problem for a status produced outside JAX-RS (router-level failures). */
    public static Problem ofStatus(int status, String instance) {
        return forStatus(status, instance, null);
    }

    /** The frozen public status for an exception. */
    public static int statusOf(Throwable t) {
        Throwable cause = unwrap(t);
        if (cause instanceof ConstraintViolationException) {
            return 400;
        }
        if (cause instanceof fr.demo.souscription.gateway.edge.CsrfValidationException) {
            return 403;
        }
        if (isTimeout(cause)) {
            return 504;
        }
        if (cause instanceof CircuitBreakerOpenException) {
            return 503;
        }
        // Rejet par un bulkhead : la dependance est saine mais la passerelle a atteint la
        // concurrence qu'elle s'autorise sur cette arete. Le refus est transitoire, donc 503 +
        // Retry-After, jamais 500 (voir docs/latency-budget.md).
        if (cause instanceof org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException) {
            return 503;
        }
        if (cause instanceof DownstreamHttpException downstream) {
            return downstreamStatus(downstream.downstreamStatus());
        }
        // Dependance injoignable au niveau transport : connexion refusee, connexion fermee par le
        // pair, hote inconnu, ou poignee de main TLS refusee (certificat serveur invalide ou
        // absent). Aucune reponse applicative n'a ete obtenue : l'echec est transitoire du point de
        // vue de l'appelant, donc 503 + Retry-After et jamais 500 (plan 24 / docs/error-mapping.md).
        // Une poignee de main refusee n'autorise aucun repli en clair : elle echoue, point.
        if (isUnreachable(cause)) {
            return 503;
        }
        if (cause instanceof WebApplicationException web) {
            int status = web.getResponse().getStatus();
            return TITLES.containsKey(status) ? status : 500;
        }
        return 500;
    }

    /**
     * Any timeout in the failure chain, whatever carrier wrapped it: the MicroProfile timeout, the
     * JDK one, a socket read timeout or the Vert.x one raised by the REST client's
     * {@code read-timeout}. The confirmation edge declares no retry, so this is the only thing that
     * turns a timed-out downstream write into the public 504.
     */
    private static boolean isTimeout(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException
                    || current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.SocketTimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /**
     * Whether the failure chain says the dependency was never reached: no connection, connection
     * reset or closed by the peer, unknown host, or a refused TLS handshake. Checked after
     * {@link #isTimeout(Throwable)} so a socket read timeout keeps its 504.
     */
    private static boolean isUnreachable(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (current instanceof javax.net.ssl.SSLException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.nio.channels.ClosedChannelException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("Connection refused")
                    || message.contains("Connection reset")
                    || message.contains("Connection was closed"))) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static int downstreamStatus(int downstreamStatus) {
        if (RELAYABLE_DOWNSTREAM_4XX.contains(downstreamStatus)) {
            return downstreamStatus;
        }
        if (downstreamStatus == 503) {
            // The one relayed 5xx: a downstream 503 is itself the "temporarily unavailable"
            // signal, and the public contract advertises 503 with Retry-After on every
            // downstream-backed operation. Turning it into a 502 would tell the caller the
            // failure is permanent (W4-T05).
            return 503;
        }
        return 502;
    }

    private static Problem forStatus(int status, String instance,
            List<FieldErrors.FieldError> errors) {
        String slug = SLUGS.getOrDefault(status, "internal-error");
        String title = TITLES.getOrDefault(status, TITLES.get(500));
        String detail = DETAILS.getOrDefault(status, title);
        return new Problem(TYPE_BASE + slug, title, status, detail, instance, errors);
    }

    /**
     * Unwraps the completion/CDI wrappers added by the reactive pipeline so the mapping is driven by
     * the business failure, not by its carrier.
     */
    private static Throwable unwrap(Throwable t) {
        Throwable current = t;
        while (current != null
                && (current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? t : current;
    }

    /**
     * Field-level violations in the {@code FieldErrors} entry shape, ordered by rejected field then
     * message so the answer is stable. No {@code code} is produced locally: the rule identifier
     * belongs to the validating downstream service.
     */
    private static List<FieldErrors.FieldError> fieldErrors(ConstraintViolationException violation) {
        List<FieldErrors.FieldError> errors = violation.getConstraintViolations().stream()
                .map(v -> new FieldErrors.FieldError(lastNode(String.valueOf(v.getPropertyPath())),
                        v.getMessage(), null))
                .sorted(Comparator.comparing(FieldErrors.FieldError::field)
                        .thenComparing(FieldErrors.FieldError::message))
                .toList();
        return errors.isEmpty() ? null : errors;
    }

    private static String lastNode(String propertyPath) {
        int separator = propertyPath.lastIndexOf('.');
        return separator < 0 ? propertyPath : propertyPath.substring(separator + 1);
    }
}
