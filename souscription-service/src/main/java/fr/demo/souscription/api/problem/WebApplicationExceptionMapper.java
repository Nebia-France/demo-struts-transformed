package fr.demo.souscription.api.problem;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Echecs produits par JAX-RS lui-meme, rendus dans la meme forme
 * {@code application/problem+json} (W4-T04) : {@code NotFoundException} (404),
 * {@code NotAllowedException} (405) et {@code BadRequestException} (400).
 *
 * <p>Remplace les declarations de page d'erreur du descripteur web source : aucune reponse
 * n'echappe en HTML ni avec un corps vide. Le {@code detail} est volontairement absent, le
 * message interne (identifiant de brouillon, cause technique) n'etant pas destine au client.
 */
@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {

    private static final Logger LOG = Logger.getLogger(WebApplicationExceptionMapper.class);

    @Override
    public Response toResponse(WebApplicationException exception) {
        int status = exception.getResponse() == null ? 500 : exception.getResponse().getStatus();
        if (status >= 500) {
            LOG.warnf(exception, "Echec de requete ; status=%d", status);
        } else {
            LOG.debugf("Echec de requete ; status=%d", status);
        }
        Problem problem = new Problem(type(status), title(status), status, null, null,
                Problems.correlationId(), null);
        return Response.status(status).type(Problems.PROBLEM_JSON).entity(problem).build();
    }

    private static String type(int status) {
        return switch (status) {
            case 400 -> "urn:horizon:bad-request";
            case 404 -> "urn:horizon:not-found";
            case 405 -> "urn:horizon:method-not-allowed";
            case 503 -> "urn:horizon:service-unavailable";
            default -> "urn:horizon:request-failed";
        };
    }

    private static String title(int status) {
        return switch (status) {
            case 400 -> "horizon.request.invalid";
            case 404 -> "horizon.request.not-found";
            case 405 -> "horizon.request.method-not-allowed";
            case 503 -> "horizon.service.unavailable";
            default -> "horizon.request.failed";
        };
    }
}
