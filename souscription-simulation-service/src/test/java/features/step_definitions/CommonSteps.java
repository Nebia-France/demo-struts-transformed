package features.step_definitions;

import io.cucumber.java.en.Then;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Definitions d'etapes partagees par plusieurs domaines fonctionnels.
 * Cucumber charge la glue de maniere globale : les etapes communes sont donc
 * declarees une seule fois ici pour eviter toute definition en double.
 *
 * <p>Cette copie de module ne porte que les etapes communes effectivement utilisees par
 * {@code features/simulation/simulation.feature}. Les etapes communes des autres domaines
 * (navigation Struts, session, persistance des souscriptions) restent dans les stubs partages
 * a la racine du depot, propriete des applications soeurs.
 */
public class CommonSteps {

    @Then("une erreur {string} est levee")
    public void uneErreurEstLevee(String message) {
        RuntimeException error = SimulationScenarioState.current().error;
        assertNotNull(error, "aucune exception n'a ete levee");
        assertEquals(message, error.getMessage());
    }

    @Then("le champ {string} est en erreur : {word}")
    public void leChampEstEnErreur(String champ, String attendu) {
        // Les libelles du scenario (initial / monthly / years) sont traduits en noms de propriete
        // du contrat REST par SimulationSteps.wireField : le feature garde ses libelles legacy.
        String field = SimulationSteps.wireField(champ);
        Map<String, String> errors = SimulationScenarioState.current().errors;
        assertNotNull(errors, "aucune validation n'a ete declenchee");
        boolean expected = "oui".equals(attendu);
        assertEquals(expected, errors.containsKey(field),
                () -> "champ " + field + ", erreurs = " + errors);
    }
}
