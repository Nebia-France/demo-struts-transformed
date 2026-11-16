package fr.demo.souscription.gateway.error;

import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps every JAX-RS {@link WebApplicationException} to problem+json, overriding the built-in
 * handler that would otherwise answer with an empty body or an HTML page.
 *
 * <p>Covers the 400, 403, 404 and 409 statuses of the source error pages, whatever the concrete
 * exception class raised (including {@code DownstreamNotFoundException} and
 * {@code IdempotencyConflictException}). A status outside the published table is normalised to 500
 * by {@link ProblemFactory}.
 */
@Provider
public class WebApplicationProblemMapper implements ExceptionMapper<WebApplicationException> {

    @Inject
    ProblemResponses problems;

    @Override
    public Response toResponse(WebApplicationException e) {
        return problems.toResponse(e);
    }
}
