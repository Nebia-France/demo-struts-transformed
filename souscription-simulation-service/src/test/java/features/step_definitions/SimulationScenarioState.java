package features.step_definitions;

import fr.demo.souscription.simulation.model.Simulation;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Etat d'un scenario Gherkin, partage entre {@link SimulationSteps} et {@link CommonSteps}.
 *
 * <p>Cucumber instancie une glue par classe et par scenario : sans conteneur d'injection, le
 * seul point de partage entre les deux classes de glue est cet etat porte par un ThreadLocal,
 * remis a zero par le hook {@code @Before} de {@link SimulationSteps}.
 */
final class SimulationScenarioState {

    private static final ThreadLocal<SimulationScenarioState> CURRENT =
            ThreadLocal.withInitial(SimulationScenarioState::new);

    /** Taux annuel du produit retenu, fourni par le Background (jamais lu dans un catalogue). */
    BigDecimal annualRate;

    /** Resultat du calcul de projection. */
    Simulation result;

    /** Exception levee par le garde du moteur de calcul. */
    RuntimeException error;

    /** Erreurs de validation par champ (cles du contrat REST : initialAmount / monthlyAmount / durationYears). */
    Map<String, String> errors;

    /** Montant normalise par le validateur d'entrees ({@code null} = format refuse). */
    BigDecimal normalisedAmount;

    static SimulationScenarioState current() {
        return CURRENT.get();
    }

    static void reset() {
        CURRENT.remove();
    }
}
