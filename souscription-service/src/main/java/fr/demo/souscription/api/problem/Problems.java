package fr.demo.souscription.api.problem;

import fr.demo.souscription.observability.CorrelationFilter;

/** Constantes partagees par les {@code ExceptionMapper} du module (W4-T04). */
final class Problems {

    /** Media type unique des reponses d'erreur du service : aucune reponse HTML. */
    static final String PROBLEM_JSON = "application/problem+json";

    /**
     * Identifiant de correlation de la requete en cours, lu dans le MDC pose par
     * {@link CorrelationFilter} (W5-T01) : le corps d'erreur cite la meme valeur que les lignes
     * de journal de la requete et que l'en-tete {@code X-Correlation-Id} de la reponse.
     */
    static String correlationId() {
        return CorrelationFilter.currentCorrelationId();
    }

    private Problems() {
    }
}
