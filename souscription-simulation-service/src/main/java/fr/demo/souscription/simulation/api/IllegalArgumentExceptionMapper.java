package fr.demo.souscription.simulation.api;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Le garde de SimulationService.calculate rejette les paramètres hors bornes avec
 * IllegalArgumentException : c'est une erreur client (400), jamais une panne serveur (500),
 * sinon la politique de retry de l'appelant et les métriques du plan 21 seraient faussées.
 */
@Provider
public class IllegalArgumentExceptionMapper implements ExceptionMapper<IllegalArgumentException> {

    /** Message legacy (ApplicationResources / garde du moteur de calcul), repris à l'octet près. */
    private static final String DETAIL = "Paramètres de simulation invalides";

    @Context
    HttpHeaders headers;

    @Override
    public Response toResponse(IllegalArgumentException exception) {
        // Le message d'origine n'est jamais recopié : seul le texte legacy figé est exposé.
        return ProblemJson.response(400, "Validation failed", DETAIL, null, headers);
    }
}
