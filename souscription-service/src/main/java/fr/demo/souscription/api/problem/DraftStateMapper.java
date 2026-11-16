package fr.demo.souscription.api.problem;

import fr.demo.souscription.application.DraftStateException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Conflit d'etat du brouillon rendu en <b>409</b> {@code application/problem+json} (W4-T04).
 *
 * <p>Remplace le {@code response.sendError(409)} du source et ses redirections de reprise :
 * cle de confirmation divergente et parcours incomplet (code {@code PRECONDITION_SEQUENCE})
 * produisent le meme corps structure, sans page d'erreur.
 */
@Provider
public class DraftStateMapper implements ExceptionMapper<DraftStateException> {

    private static final Logger LOG = Logger.getLogger(DraftStateMapper.class);

    @Override
    public Response toResponse(DraftStateException exception) {
        LOG.debugf("Conflit d'etat du brouillon ; code=%s", exception.code());
        Problem problem = new Problem("urn:horizon:draft-state-conflict",
                "horizon.draft.state-conflict", 409, null, null, Problems.correlationId(),
                List.of(new Problem.FieldError(exception.field(), messageKey(exception.code()),
                        exception.code())));
        return Response.status(409).type(Problems.PROBLEM_JSON).entity(problem).build();
    }

    private static String messageKey(String code) {
        if (DraftStateException.CONFIRMATION_KEY_MISMATCH.equals(code)) {
            return "confirmation.key.mismatch";
        }
        if (DraftStateException.DRAFT_CONCURRENT_UPDATE.equals(code)) {
            return "draft.concurrent.update";
        }
        return "confirmation.sequence.incomplete";
    }
}
