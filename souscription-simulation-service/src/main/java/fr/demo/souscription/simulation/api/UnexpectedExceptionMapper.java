package fr.demo.souscription.simulation.api;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Dernier recours : toute défaillance non mappée devient un problem+json générique. Le détail
 * technique part dans les logs structurés (plan 22), jamais dans le corps de la réponse
 * (surface non authentifiée, NFG-008). La distinction 4xx / 5xx reste contractuelle : seuls les
 * 502/503/504 sont rejoués une fois par l'appelant.
 */
@Provider
public class UnexpectedExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(UnexpectedExceptionMapper.class);

    @Context
    HttpHeaders headers;

    @Override
    public Response toResponse(Throwable throwable) {
        if (throwable instanceof WebApplicationException webException) {
            int status = webException.getResponse().getStatus();
            if (status < 500) {
                return ProblemJson.response(status, "Request rejected", null, null, headers);
            }
        }
        LOG.error("Unexpected failure while handling a simulation request", throwable);
        return ProblemJson.response(500, "Internal server error", null, null, headers);
    }
}
