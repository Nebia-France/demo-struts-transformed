package fr.demo.souscription.catalogue.api;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Filet de securite : toute erreur non mappee devient un 500 {@code problem+json} statique.
 * Aucune trace, aucun message d'exception et aucun nom de classe n'est divulgue au client.
 */
@Provider
public class GenericExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(GenericExceptionMapper.class);

    @Override
    public Response toResponse(Throwable throwable) {
        if (throwable instanceof WebApplicationException wae) {
            return wae.getResponse();
        }
        LOG.error("Erreur interne du service catalogue", throwable);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(ProblemMediaType.APPLICATION_PROBLEM_JSON)
                .entity(new ProblemDetail(
                        "https://horizon.demo/problems/erreur-interne",
                        "Erreur interne", 500,
                        "Le service catalogue n'a pas pu traiter la demande.",
                        "/v1/products"))
                .build();
    }
}
