package features.step_definitions;

import fr.demo.souscription.simulation.model.Simulation;
import fr.demo.souscription.simulation.service.SimulationInputValidator;
import fr.demo.souscription.simulation.service.SimulationService;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Etapes du domaine "Simulation du placement".
 * Source : fr.demo.souscription.service.SimulationService,
 *          fr.demo.souscription.service.InputValidator,
 *          fr.demo.souscription.model.Simulation
 *
 * <p>Cible : fr.demo.souscription.simulation.service.SimulationService et
 * SimulationInputValidator, invoques en process. Les deux beans n'ont aucune dependance
 * CDI obligatoire (l'horloge de SimulationService est initialisee a Europe/Paris par son
 * initialiseur de champ), exactement comme dans les tests unitaires portes. Le meme
 * comportement traverse en plus le transport /v1/simulations dans les tests de contrat
 * REST Assured (SimulationValidationIT, SimulationContractIT) : voir README.md, section
 * "Gherkin".
 */
public class SimulationSteps {

    private final SimulationInputValidator validator = new SimulationInputValidator();
    private final SimulationService simulationService = new SimulationService();

    @Before
    public void resetScenarioState() {
        SimulationScenarioState.reset();
    }

    @Given("le produit retenu est {string} avec un taux annuel de {string}")
    public void leProduitRetenuEstAvecUnTauxAnnuelDe(String code, String taux) {
        SimulationScenarioState.current().annualRate = new BigDecimal(taux);
    }

    @When("le systeme calcule la simulation avec un initial de {string}, une mensualite de {string} et une duree de {int} ans")
    public void leSystemeCalculeLaSimulation(String initial, String mensualite, int annees) {
        SimulationScenarioState state = SimulationScenarioState.current();
        state.result = simulationService.calculate(validator.amount(initial),
                validator.amount(mensualite), annees, state.annualRate);
    }

    @When("le systeme calcule la simulation avec les parametres bruts initial {string}, mensualite {string}, duree {int} et taux {string}")
    public void leSystemeCalculeLaSimulationAvecLesParametresBruts(String initial, String mensualite,
                                                                   int annees, String taux) {
        SimulationScenarioState state = SimulationScenarioState.current();
        try {
            state.result = simulationService.calculate(decimal(initial), decimal(mensualite),
                    annees, decimal(taux));
        } catch (IllegalArgumentException e) {
            state.error = e;
        }
    }

    /**
     * Libelles du feature (contrat Gherkin, inchange) vers les noms de propriete du contrat
     * REST, qui sont ceux portes par {@code errors[].field} ({@code SimulationValidationIT}).
     * La traduction est faite par la definition d'etape : ni le feature ni la reponse ne bougent.
     */
    private static final Map<String, String> WIRE_FIELDS = Map.of(
            "initial", "initialAmount",
            "monthly", "monthlyAmount",
            "years", "durationYears");

    static String wireField(String label) {
        String field = WIRE_FIELDS.get(label);
        assertNotNull(field, () -> "libelle de champ inconnu : " + label);
        return field;
    }

    @When("l'utilisateur renseigne un initial de {string}, une mensualite de {string} et une duree de {string}")
    public void lUtilisateurRenseigneInitialMensualiteDuree(String initial, String mensualite, String duree) {
        Map<String, String> errors = new LinkedHashMap<>();
        validator.simulationErrors(initial, mensualite, duree)
                .forEach((field, message) -> errors.put(wireField(field), message));
        SimulationScenarioState.current().errors = errors;
    }

    @When("le systeme normalise le montant {string}")
    public void leSystemeNormaliseLeMontant(String saisie) {
        SimulationScenarioState.current().normalisedAmount = validator.amount(saisie);
    }

    @Then("aucune erreur de validation de la simulation n'est signalee")
    public void aucuneErreurDeValidationDeLaSimulationNEstSignalee() {
        assertTrue(errors().isEmpty(), () -> "erreurs inattendues : " + errors());
    }

    @Then("la simulation retourne un versement initial de {string}")
    public void laSimulationRetourneUnVersementInitialDe(String montant) {
        assertEquals(montant, result().initial().toPlainString());
    }

    @Then("la simulation retourne une mensualite de {string}")
    public void laSimulationRetourneUneMensualiteDe(String montant) {
        assertEquals(montant, result().monthly().toPlainString());
    }

    @Then("la simulation retourne une duree de {int} ans")
    public void laSimulationRetourneUneDureeDeAns(int annees) {
        assertEquals(annees, result().years());
    }

    @Then("le total verse de la simulation est {string}")
    public void leTotalVerseDeLaSimulationEst(String montant) {
        assertEquals(montant, result().contributed().toPlainString());
    }

    @Then("le capital final de la simulation est superieur au total verse")
    public void leCapitalFinalDeLaSimulationEstSuperieurAuTotalVerse() {
        Simulation simulation = result();
        assertTrue(simulation.finalCapital().compareTo(simulation.contributed()) > 0,
                () -> simulation.finalCapital() + " n'est pas superieur a " + simulation.contributed());
    }

    @Then("le capital final de la simulation est egal au total verse")
    public void leCapitalFinalDeLaSimulationEstEgalAuTotalVerse() {
        Simulation simulation = result();
        assertEquals(0, simulation.finalCapital().compareTo(simulation.contributed()),
                () -> simulation.finalCapital() + " != " + simulation.contributed());
    }

    @Then("les gains de la simulation sont egaux au capital final moins le total verse")
    public void lesGainsDeLaSimulationSontEgauxAuCapitalFinalMoinsLeTotalVerse() {
        Simulation simulation = result();
        assertEquals(0, simulation.gains()
                .compareTo(simulation.finalCapital().subtract(simulation.contributed())));
    }

    @Then("les gains de la simulation sont nuls")
    public void lesGainsDeLaSimulationSontNuls() {
        assertEquals(0, result().gains().signum(), () -> "gains = " + result().gains());
    }

    @Then("le montant normalise vaut {string}")
    public void leMontantNormaliseVaut(String valeur) {
        BigDecimal normalised = SimulationScenarioState.current().normalisedAmount;
        assertNotNull(normalised, "le montant a ete refuse par le validateur");
        assertEquals(valeur, normalised.toPlainString());
    }

    @Then("le montant normalise est absent")
    public void leMontantNormaliseEstAbsent() {
        assertNull(SimulationScenarioState.current().normalisedAmount);
    }

    /** Une cellule vide de la table d'exemples represente un parametre absent. */
    private static BigDecimal decimal(String raw) {
        return raw == null || raw.isBlank() ? null : new BigDecimal(raw);
    }

    private Simulation result() {
        Simulation simulation = SimulationScenarioState.current().result;
        assertNotNull(simulation, "aucune simulation n'a ete calculee");
        return simulation;
    }

    private Map<String, String> errors() {
        Map<String, String> errors = SimulationScenarioState.current().errors;
        assertNotNull(errors, "aucune validation n'a ete declenchee");
        return errors;
    }
}
