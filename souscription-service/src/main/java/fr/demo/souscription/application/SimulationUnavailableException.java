package fr.demo.souscription.application;

/**
 * Indisponibilite du recalcul distant de la simulation (W6-T02).
 *
 * <p>Jumelle de {@link StorageUnavailableException} et derivee d'elle pour partager son unique
 * mapper : les deux pannes ont la meme semantique de reprise — aucune confirmation n'a ete emise,
 * la saisie est conservee dans le brouillon et la requete peut etre rejouee a l'identique. Le
 * code porte par le corps {@code problem+json} est en revanche distinct
 * ({@code SIMULATION_UNAVAILABLE_RETRYABLE}), la SPA devant pouvoir distinguer les deux causes.
 *
 * <p>Aucun repli n'existe : la confirmation echoue en <b>503</b> plutot que de persister une
 * projection non verifiee ou une valeur fournie par le client.
 */
public class SimulationUnavailableException extends StorageUnavailableException {

    private static final long serialVersionUID = 1L;

    public SimulationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
