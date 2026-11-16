package fr.demo.souscription.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fr.demo.souscription.api.dto.SimulationResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test de contrat consommateur de la simulation (W6-T02).
 *
 * <p>Porte {@code src/test/java/fr/demo/souscription/service/SimulationServiceTest.java} : les
 * trois methodes du source sont conservees, mais l'arithmetique n'appartient plus a ce service.
 * Elle est detenue par souscription-simulation-service, donc les valeurs attendues sont celles
 * d'un bouchon derive du contrat gele ({@code openapi.yaml} et
 * {@code shared-schemas/SimulationResult.json}).
 *
 * <p>Scenarios consommes de {@code features/simulation/simulation.feature} couverts ici :
 * <ul>
 *   <li>Simulation d'un placement standard ;</li>
 *   <li>Parametres de simulation rejetes par le moteur de calcul ;</li>
 *   <li>Simulation avec une hypothese de progression nulle ;</li>
 *   <li>Simulation sans mensualite.</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(RemoteSimulationProfile.class)
class SimulationClientContractTest {

    private static SimulationStubServer stub;

    @Inject
    SimulationLookup simulations;

    @BeforeAll
    static void startStub() throws IOException {
        stub = new SimulationStubServer();
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @BeforeEach
    void resetStub() {
        stub.reset();
        stub.serveComputation();
    }

    /** Scenario « Simulation d'un placement standard ». */
    @Test
    void simulationDUnPlacementStandard() {
        SimulationResult result = compute("1000.00", "100.00", 10, "0.020000");

        assertEquals(SimulationStubServer.expectedFinalCapital("1000.00", "100.00", 10,
                        "0.020000"), result.finalCapital(),
                "la projection persistee est celle retournee par le fournisseur, jamais un "
                + "recalcul local");
        assertEquals("13000.00", result.contributed(),
                "contributed = initial + mensualite * annees * 12, a l'echelle 2");
        assertEquals("1000.00", result.initialAmount());
        assertEquals("100.00", result.monthlyAmount());
        assertEquals(10, result.durationYears());
        assertEquals(2, new BigDecimal(result.finalCapital()).scale(),
                "les montants voyagent en chaines d'echelle 2 : un nombre JSON perdrait les "
                + "zeros de fin");
        assertEquals(1, stub.requests(), "un seul appel au fournisseur par simulation");
    }

    /**
     * Methode portee : {@code zeroRateKeepsExactlyTheContributions}.
     * Scenario « Simulation avec une hypothese de progression nulle ».
     */
    @Test
    void zeroRateKeepsExactlyTheContributions() {
        SimulationResult result = compute("1200.00", "100.00", 1, "0.000000");

        assertEquals("2400.00", result.finalCapital(),
                "sans progression, le capital final est exactement la somme versee");
        assertEquals("2400.00", result.contributed());
        assertEquals("0.00", new BigDecimal(result.finalCapital())
                        .subtract(new BigDecimal(result.contributed())).toPlainString(),
                "les gains derives sont nuls, comme dans le source");
    }

    /** Scenario « Simulation sans mensualite ». */
    @Test
    void simulationSansMensualite() {
        SimulationResult result = compute("1000.00", "0.00", 5, "0.040000");

        assertEquals("1000.00", result.contributed(),
                "sans mensualite, la somme versee est le seul versement initial");
        assertEquals(SimulationStubServer.expectedFinalCapital("1000.00", "0.00", 5, "0.040000"),
                result.finalCapital());
    }

    /**
     * Methode portee : {@code agreesWithIndependentClosedForm}. La forme fermee independante
     * reste la verification de la projection, desormais appliquee a la valeur retournee par le
     * fournisseur.
     */
    @ParameterizedTest
    @CsvSource({"100.00,0.00,1,0.020000", "1000.00,100.00,5,0.040000",
                "100000.00,2000.00,30,0.060000", "100.00,2000.00,1,0.060000"})
    void agreesWithIndependentClosedForm(String initial, String monthly, int years, String rate) {
        SimulationResult result = compute(initial, monthly, years, rate);

        double monthlyRate = Double.parseDouble(rate) / 12;
        double multiplier = Math.pow(1 + monthlyRate, years * 12);
        double expected = Double.parseDouble(initial) * multiplier
                + Double.parseDouble(monthly) * (multiplier - 1) / monthlyRate;
        assertEquals(expected, Double.parseDouble(result.finalCapital()), 0.005,
                "la valeur distante doit rester celle de la capitalisation mensuelle du source");
        assertEquals(2, new BigDecimal(result.finalCapital()).scale());
        assertEquals(SimulationStubServer.expectedContributed(initial, monthly, years),
                result.contributed());
    }

    /**
     * Methode portee : {@code refusesInvalidParameters}.
     * Scenario « Parametres de simulation rejetes par le moteur de calcul » : le 400 du
     * fournisseur reste une erreur de parametres (rendue 400 par ce service) et n'est jamais
     * reessaye ni convertie en 5xx.
     */
    @ParameterizedTest
    @CsvSource({"99.00,0.00,1", "100001.00,0.00,1", "100.00,-1.00,1", "100.00,2001.00,1",
                "100.00,0.00,0", "100.00,0.00,31", "100.001,0.00,1"})
    void refusesInvalidParameters(String initial, String monthly, int years) {
        stub.reset();

        assertThrows(IllegalArgumentException.class,
                () -> compute(initial, monthly, years, "0.020000"),
                "les bornes refusees par le moteur de calcul du source le restent cote "
                + "fournisseur : " + initial + "/" + monthly + "/" + years);
        assertEquals(1, stub.requests(),
                "un 400 est une condition non rejouable du contrat : exactement une requete");
    }

    private SimulationResult compute(String initial, String monthly, int years, String rate) {
        return simulations.compute(
                new SimulationComputeRequest(initial, monthly, years, rate));
    }
}
