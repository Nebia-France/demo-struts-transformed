package fr.demo.souscription.simulation.api;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;

/**
 * Pendant de BulkheadExceptionMapper pour le timeout serveur de 2 500 ms posé sur
 * SimulationResource.compute. Strictement inférieur au timeout appelant de 3 000 ms des contrats
 * figés : l'appelant reçoit un 503 problem+json explicite plutôt qu'un abandon côté client.
 */
@Provider
public class TimeoutExceptionMapper implements ExceptionMapper<TimeoutException> {

    @Context
    HttpHeaders headers;

    @Override
    public Response toResponse(TimeoutException exception) {
        return ProblemJson.response(503, BulkheadExceptionMapper.TITLE,
                BulkheadExceptionMapper.DETAIL, null, headers);
    }
}
