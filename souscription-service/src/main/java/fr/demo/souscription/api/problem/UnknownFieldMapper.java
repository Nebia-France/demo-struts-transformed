package fr.demo.souscription.api.problem;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Champ inconnu dans un corps de requete, rendu en <b>400</b> {@code application/problem+json}.
 *
 * <p>Le refus lui-meme vient de {@code quarkus.jackson.fail-on-unknown-properties=true} : le
 * contrat est gele, un champ hors contrat est une erreur du client. Sans ce mappeur, le refus est
 * rendu par le mappeur par defaut du cadre, qui repond {@code application/json} au lieu du type
 * {@code problem+json} du contrat et expose le nom de la classe de transport, le nom de l'attribut
 * refuse et sa position dans le flux. Le {@code detail} reste absent : la cause n'est journalisee
 * qu'au niveau debogage.
 */
@Provider
public class UnknownFieldMapper implements ExceptionMapper<UnrecognizedPropertyException> {

    private static final Logger LOG = Logger.getLogger(UnknownFieldMapper.class);

    @Override
    public Response toResponse(UnrecognizedPropertyException exception) {
        LOG.debugf("Corps de requete refuse ; champ inconnu=%s", exception.getPropertyName());
        Problem problem = new Problem("urn:horizon:bad-request", "horizon.request.invalid",
                400, null, null, Problems.correlationId(), null);
        return Response.status(400).type(Problems.PROBLEM_JSON).entity(problem).build();
    }
}
