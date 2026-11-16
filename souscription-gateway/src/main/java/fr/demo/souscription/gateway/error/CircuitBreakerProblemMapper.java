package fr.demo.souscription.gateway.error;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Maps a rejection by an open circuit breaker to 503 with a {@code Retry-After} header, so the SPA
 * and any intermediary know the rejection is transient.
 */
@Provider
public class CircuitBreakerProblemMapper implements ExceptionMapper<CircuitBreakerOpenException> {

    @Inject
    ProblemResponses problems;

    @Override
    public Response toResponse(CircuitBreakerOpenException e) {
        return problems.toResponse(e);
    }
}
