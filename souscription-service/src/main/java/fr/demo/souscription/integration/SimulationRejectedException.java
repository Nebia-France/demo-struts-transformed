package fr.demo.souscription.integration;

import fr.demo.souscription.api.dto.FieldErrors;
import java.util.List;

/**
 * Parametres refuses par le moteur de calcul distant (400 du contrat consomme), condition
 * <b>non rejouable</b>.
 *
 * <p>Derive d'{@link IllegalArgumentException} : le refus en memoire du service de simulation du
 * source levait cette exception, et les appelants la traduisent deja en <b>400</b>. La classe
 * d'erreur rendue au client est donc inchangee par le passage a un appel distant, et un refus de
 * parametres ne devient jamais un 5xx.
 */
public class SimulationRejectedException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * Verdicts par champ du fournisseur, relayes tels quels : ce sont les erreurs que le
     * formulaire source affichait a cote du champ fautif. Vide lorsque le fournisseur n'en
     * fournit aucun.
     */
    private final transient List<FieldErrors.FieldError> relayed;

    public SimulationRejectedException(String message) {
        this(message, List.of());
    }

    public SimulationRejectedException(String message, List<FieldErrors.FieldError> relayed) {
        super(message);
        this.relayed = List.copyOf(relayed);
    }

    public List<FieldErrors.FieldError> relayed() {
        return relayed;
    }
}
