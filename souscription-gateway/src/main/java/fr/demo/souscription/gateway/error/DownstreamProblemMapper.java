package fr.demo.souscription.gateway.error;

import fr.demo.souscription.gateway.client.DownstreamHttpException;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps a downstream HTTP error, a failure class absent from the monolith where the business logic
 * ran in-process. A relayable downstream 4xx keeps its status, any other downstream status becomes
 * a 502: see {@code docs/error-mapping.md}.
 */
@Provider
public class DownstreamProblemMapper implements ExceptionMapper<DownstreamHttpException> {

    @Inject
    ProblemResponses problems;

    @Override
    public Response toResponse(DownstreamHttpException e) {
        return problems.toResponse(e);
    }
}
