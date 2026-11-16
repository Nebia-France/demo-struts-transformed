package fr.demo.souscription.api.problem;

import fr.demo.souscription.application.SimulationUnavailableException;
import fr.demo.souscription.application.StorageUnavailableException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Indisponibilite de l'enregistrement rendue en <b>503</b> {@code application/problem+json}
 * (W4-T04, ecart FG-003).
 *
 * <p>Porte la semantique du {@code catch (SQLException)} du source : aucune confirmation n'a ete
 * emise, la saisie est conservee et la requete peut etre rejouee. Le caractere rejouable reste
 * visible dans la reponse par le code {@code STORAGE_UNAVAILABLE_RETRYABLE}, dont la SPA resout
 * la cle de message. Un echec de stockage ne renvoie jamais 500.
 *
 * <p>Le {@code SQLState} est journalise cote serveur et jamais renvoye : le corps ne contient ni
 * {@code SQLState}, ni trace d'appel, ni texte SQL.
 */
@Provider
public class StorageUnavailableMapper implements ExceptionMapper<StorageUnavailableException> {

    private static final Logger LOG = Logger.getLogger(StorageUnavailableMapper.class);

    /** Code stable attendu par la SPA pour proposer un nouvel essai. */
    public static final String STORAGE_UNAVAILABLE_RETRYABLE = "STORAGE_UNAVAILABLE_RETRYABLE";

    /**
     * Code de l'indisponibilite du recalcul distant (W6-T02) : meme semantique de reprise que
     * ci-dessus, cause distincte pour la SPA.
     */
    public static final String SIMULATION_UNAVAILABLE_RETRYABLE = "SIMULATION_UNAVAILABLE_RETRYABLE";

    @Override
    public Response toResponse(StorageUnavailableException exception) {
        if (exception instanceof SimulationUnavailableException) {
            LOG.warnf("Recalcul de simulation indisponible ; aucune confirmation emise : %s",
                    exception.getMessage());
            Problem unavailable = new Problem("urn:horizon:simulation-unavailable",
                    "horizon.simulation.unavailable", 503, null, null, Problems.correlationId(),
                    List.of(new Problem.FieldError("simulation",
                            "confirmation.simulation.unavailable",
                            SIMULATION_UNAVAILABLE_RETRYABLE)));
            return Response.status(503).type(Problems.PROBLEM_JSON).entity(unavailable).build();
        }
        LOG.warnf("Enregistrement refuse ; sqlState=%s", exception.sqlState());
        Problem problem = new Problem("urn:horizon:storage-unavailable",
                "horizon.storage.unavailable", 503, null, null, Problems.correlationId(),
                List.of(new Problem.FieldError("storage", "confirmation.storage.unavailable",
                        STORAGE_UNAVAILABLE_RETRYABLE)));
        return Response.status(503).type(Problems.PROBLEM_JSON).entity(problem).build();
    }
}
