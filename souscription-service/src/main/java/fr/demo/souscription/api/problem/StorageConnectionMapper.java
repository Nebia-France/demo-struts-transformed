package fr.demo.souscription.api.problem;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.List;
import org.hibernate.exception.JDBCConnectionException;
import org.jboss.logging.Logger;

/**
 * Base des souscriptions injoignable rendue en <b>503</b> {@code application/problem+json}
 * (W8-T04, constat de parite du scenario Gherkin rewrite « Acces aux services avant
 * initialisation »).
 *
 * <p>Sans ce mappeur, une lecture {@code /v1/*} executee alors que le pool Agroal ne peut obtenir
 * aucune connexion remontait au gestionnaire d'erreur par defaut du cadre : <b>500</b>
 * {@code text/plain} avec la trace d'appel du pilote JDBC dans le corps. Le service n'est pas
 * pret dans cet etat (sonde {@code database} {@code DOWN}) ; la reponse est donc la meme que
 * celle d'une indisponibilite de stockage : 503, rejouable, sans {@code SQLState} ni trace.
 */
@Provider
public class StorageConnectionMapper implements ExceptionMapper<JDBCConnectionException> {

    private static final Logger LOG = Logger.getLogger(StorageConnectionMapper.class);

    @Override
    public Response toResponse(JDBCConnectionException exception) {
        LOG.warnf("Base des souscriptions injoignable ; sqlState=%s", exception.getSQLState());
        Problem problem = new Problem("urn:horizon:storage-unavailable",
                "horizon.storage.unavailable", 503, null, null, Problems.correlationId(),
                List.of(new Problem.FieldError("storage", "confirmation.storage.unavailable",
                        StorageUnavailableMapper.STORAGE_UNAVAILABLE_RETRYABLE)));
        return Response.status(503).type(Problems.PROBLEM_JSON).entity(problem).build();
    }
}
