package fr.demo.souscription.simulation.api;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;

/**
 * Rejet rapide en surcharge (W4-T03). Au-delà du bulkhead (20 appels concurrents, file de 40),
 * SmallRye Fault Tolerance lève BulkheadException : c'est une indisponibilité temporaire, donc
 * 503 application/problem+json, jamais 500 (qui ferait croire à un bug) et jamais 429 (réservé au
 * rate limiting de la politique de sécurité, plan 26). Les contrats figés autorisent l'appelant à
 * rejouer un 503 une seule fois.
 */
@Provider
public class BulkheadExceptionMapper implements ExceptionMapper<BulkheadException> {

    /** Texte figé : aucun détail interne (capacité, file, threads) n'est exposé (NFG-008). */
    static final String DETAIL = "Service momentanément saturé, réessayez";
    static final String TITLE = "Service unavailable";

    @Context
    HttpHeaders headers;

    @Override
    public Response toResponse(BulkheadException exception) {
        // Pas de tableau errors : ce n'est pas un problème de validation de champs.
        return ProblemJson.response(503, TITLE, DETAIL, null, headers);
    }
}
