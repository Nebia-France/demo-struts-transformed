package fr.demo.souscription.gateway.error;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Last-resort mapper, replacing the {@code java.lang.Throwable} error page of the source
 * deployment descriptor: anything not matched by a more specific mapper becomes a 500
 * {@code application/problem+json} with no stack trace and no internal detail.
 */
@Provider
public class ProblemMapper implements ExceptionMapper<Throwable> {

    @Inject
    ProblemResponses problems;

    @Override
    public Response toResponse(Throwable t) {
        return problems.toResponse(t);
    }
}
