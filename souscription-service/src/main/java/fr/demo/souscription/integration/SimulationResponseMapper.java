package fr.demo.souscription.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.demo.souscription.api.dto.FieldErrors;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/**
 * Traduction des reponses d'erreur de la simulation en exceptions dont le caractere rejouable est
 * explicite (W6-T02) :
 * <ul>
 *   <li>400 : {@link SimulationRejectedException} — parametres refuses par le moteur de calcul,
 *       non rejouable, rendu 400 par l'appelant ;</li>
 *   <li>autre 4xx, dont 429 : {@link SimulationClientException} — non rejouable, rendu 503 ;</li>
 *   <li>5xx : {@link WebApplicationException} — seule famille citee par le {@code retryOn} de
 *       {@link SimulationClient}, donc la seule reessayee.</li>
 * </ul>
 *
 * <p>Le corps {@code problem+json} du fournisseur n'est pas recopie, a l'exception du tableau
 * {@code errors} d'un 400 : ces verdicts par champ sont relayes tels quels, car ce sont les
 * erreurs que l'utilisateur doit voir a cote du champ fautif.
 */
public class SimulationResponseMapper implements ResponseExceptionMapper<RuntimeException> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public boolean handles(int status, MultivaluedMap<String, Object> headers) {
        return status >= 400;
    }

    @Override
    public RuntimeException toThrowable(Response response) {
        int status = response.getStatus();
        if (status == 400) {
            return new SimulationRejectedException(
                    "parametres de simulation refuses par le moteur de calcul",
                    relayedErrors(response));
        }
        if (status < 500) {
            return new SimulationClientException(status,
                    "appel a la simulation refuse ; status=" + status);
        }
        return new WebApplicationException("simulation en echec ; status=" + status, status);
    }

    /**
     * Verdicts par champ du corps {@code problem+json} du fournisseur, seule partie de la reponse
     * distante relayee a l'appelant : ce sont les erreurs que le formulaire source rendait a cote
     * du champ fautif, et les bornes sont les memes que celles validees localement. Le reste du
     * corps distant ({@code type}, {@code title}, {@code correlationId} du fournisseur) n'est pas
     * recopie.
     */
    private static List<FieldErrors.FieldError> relayedErrors(Response response) {
        String body;
        try {
            body = response.hasEntity() ? response.readEntity(String.class) : null;
        } catch (ProcessingException | IllegalStateException unreadable) {
            return List.of();
        }
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode errors = JSON.readTree(body).path("errors");
            if (!errors.isArray()) {
                return List.of();
            }
            List<FieldErrors.FieldError> relayed = new ArrayList<>();
            for (JsonNode error : errors) {
                relayed.add(new FieldErrors.FieldError(text(error, "field"),
                        text(error, "message"), text(error, "code")));
            }
            return List.copyOf(relayed);
        } catch (JsonProcessingException malformed) {
            return List.of();
        }
    }

    private static String text(JsonNode error, String field) {
        JsonNode value = error.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
