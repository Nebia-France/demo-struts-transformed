package fr.demo.souscription.gateway.client;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Raised by a gateway fallback when a dependency could not be reached at all — no connection, or a
 * circuit breaker holding the edge open — so the gateway answers 503 with {@code Retry-After}
 * instead of propagating a transport exception.
 *
 * <p>Only declared on the catalogue edge: a degraded "catalogue momentarily unavailable" answer is
 * acceptable there, whereas a fallback on the confirmation write would mask a possible data loss
 * (see {@code docs/resilience-policies.md}).
 */
public class DownstreamUnavailableException extends WebApplicationException {

    private static final long serialVersionUID = 1L;

    private final String dependency;

    public DownstreamUnavailableException(String dependency) {
        super("Dependance indisponible: " + dependency, Response.Status.SERVICE_UNAVAILABLE);
        this.dependency = dependency;
    }

    /** The dependency that could not be reached; journalised, never written to the body. */
    public String dependency() {
        return dependency;
    }
}
