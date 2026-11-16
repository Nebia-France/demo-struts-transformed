package fr.demo.souscription.gateway.error;

import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps bean-validation failures to 400 problem+json, carrying the rejected fields in the
 * {@code errors} member. Replaces the Struts {@code ActionErrors} rendering of the source: the
 * gateway returns machine-readable field errors and souscription-web owns the labels.
 */
@Provider
public class ValidationProblemMapper implements ExceptionMapper<ConstraintViolationException> {

    @Inject
    ProblemResponses problems;

    @Override
    public Response toResponse(ConstraintViolationException e) {
        return problems.toResponse(e);
    }
}
