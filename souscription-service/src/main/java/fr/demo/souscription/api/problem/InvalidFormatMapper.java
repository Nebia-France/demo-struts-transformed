package fr.demo.souscription.api.problem;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Valeur d'un type incompatible avec le contrat gele (par exemple {@code "durationYears":"5,5"}
 * la ou un entier JSON est attendu), rendue en <b>400</b> {@code application/problem+json} avec
 * une erreur sur le champ concerne (W8-T04, constat de parite du scenario Gherkin
 * « Bornes de la duree en annees »).
 *
 * <p>Sans ce mappeur, le refus etait rendu par le mappeur par defaut du cadre : 400 sans corps ni
 * {@code Content-Type}, donc sans le champ rejete que la SPA doit signaler. Le source refusait la
 * meme saisie par l'expression {@code (?:[1-9]|[12][0-9]|30)} sur {@code years} : la cle et le code
 * de la duree sont donc ceux du vocabulaire gele (ERROR_VOCABULARY.md). Aucune position dans le
 * flux ni nom de classe n'est renvoye.
 */
@Provider
public class InvalidFormatMapper implements ExceptionMapper<InvalidFormatException> {

    private static final Logger LOG = Logger.getLogger(InvalidFormatMapper.class);

    /** Champ du contrat → cle de message et code stable de ERROR_VOCABULARY.md. */
    private static final Map<String, List<String>> VOCABULARY = Map.of(
            "durationYears", List.of("simulation.durationYears.range", "DURATION_YEARS_RANGE"));

    @Override
    public Response toResponse(InvalidFormatException exception) {
        String field = fieldOf(exception);
        LOG.debugf("Corps de requete refuse ; type incompatible sur le champ %s", field);
        List<String> entry = VOCABULARY.getOrDefault(field, List.of(field + ".invalid",
                field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase() + "_INVALID"));
        Problem problem = new Problem("urn:horizon:validation-failed", "horizon.validation.failed",
                400, null, null, Problems.correlationId(),
                List.of(new Problem.FieldError(field, entry.get(0), entry.get(1))));
        return Response.status(400).type(Problems.PROBLEM_JSON).entity(problem).build();
    }

    private static String fieldOf(JsonMappingException exception) {
        List<JsonMappingException.Reference> path = exception.getPath();
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i).getFieldName() != null) {
                return path.get(i).getFieldName();
            }
        }
        return "body";
    }
}
