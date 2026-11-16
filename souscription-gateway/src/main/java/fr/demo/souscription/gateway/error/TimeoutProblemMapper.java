package fr.demo.souscription.gateway.error;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;

/**
 * Maps a downstream timeout to 504, the frozen public status of the new network failure class
 * introduced by the service split.
 */
@Provider
public class TimeoutProblemMapper implements ExceptionMapper<TimeoutException> {

    @Inject
    ProblemResponses problems;

    @Override
    public Response toResponse(TimeoutException e) {
        return problems.toResponse(e);
    }
}
