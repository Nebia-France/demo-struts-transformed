package fr.demo.souscription.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import fr.demo.souscription.api.dto.SimulationResult;
import fr.demo.souscription.contract.ContractStub.Call;
import fr.demo.souscription.contract.ContractStub.Reply;
import fr.demo.souscription.integration.SimulationComputeRequest;
import fr.demo.souscription.integration.SimulationLookup;
import fr.demo.souscription.integration.SimulationStubServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Scenarios consommes de la simulation rejoues contre un bouchon genere depuis le contrat gele
 * {@code global/contracts/souscription-service-to-souscription-simulation-service/openapi.yaml}
 * (plan 42, etape 3).
 *
 * <p>Le bouchon valide la requete contre {@code SimulationComputeRequest.json} et la reponse contre
 * {@code SimulationResult.json} / {@code FieldErrors.json}. L'arithmetique (capitalisation
 * mensuelle DECIMAL128, arrondi HALF_UP a l'echelle 2) et les bornes metier sont celles du
 * fournisseur, reprises de {@link SimulationStubServer}.
 */
@QuarkusTest
@TestProfile(ContractDrivenStubsProfile.class)
class SimulationContractStubTest {

    static final Path CONTRACT = ContractDocuments.contract(
            "souscription-service-to-souscription-simulation-service", "openapi.yaml");

    private static final String REJECTED = """
            {"type":"urn:horizon:validation-failed","title":"horizon.request.invalid","status":400}""";

    private static ContractStub stub;

    @Inject
    SimulationLookup simulations;

    @BeforeAll
    static void startStub() throws IOException {
        stub = new ContractStub(CONTRACT, ContractDrivenStubsProfile.SIMULATION_PORT,
                SimulationContractStubTest::provider);
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @BeforeEach
    void reset() {
        stub.reset();
    }

    @AfterEach
    void theWireConformedToTheContract() {
        assertEquals(List.of(), stub.violations(), "ecarts au contrat simulation");
        assertEquals(List.of(), stub.requestViolations(), "requetes non conformes au contrat");
    }

    private static Reply provider(Call call) {
        JsonNode body = call.body();
        BigDecimal initial = new BigDecimal(body.get("initialAmount").asText());
        BigDecimal monthly = new BigDecimal(body.get("monthlyAmount").asText());
        int years = body.get("durationYears").asInt();
        if (initial.compareTo(new BigDecimal("100")) < 0
                || initial.compareTo(new BigDecimal("100000")) > 0
                || monthly.compareTo(new BigDecimal("2000")) > 0) {
            return new Reply(400, REJECTED);
        }
        String contributed = initial.add(monthly.multiply(BigDecimal.valueOf(years * 12L)))
                .setScale(2, RoundingMode.HALF_UP).toPlainString();
        String finalCapital = SimulationStubServer.expectedFinalCapital(initial.toPlainString(),
                monthly.toPlainString(), years, body.get("annualRate").asText());
        return new Reply(200, """
                {"initialAmount":"%s","monthlyAmount":"%s","durationYears":%d,\
                "contributed":"%s","finalCapital":"%s"}"""
                .formatted(initial.toPlainString(), monthly.toPlainString(), years, contributed,
                        finalCapital));
    }

    private SimulationResult compute(String initial, String monthly, int years, String rate) {
        return simulations.compute(new SimulationComputeRequest(initial, monthly, years, rate));
    }

    @Test
    void theStubRoutesExactlyTheConsumedOperation() {
        assertEquals(List.of("computeSimulation"), stub.operationIds());
    }

    /** Scenario « Simulation d'un placement standard ». */
    @Test
    void simulationDUnPlacementStandard() {
        SimulationResult result = compute("1000.00", "100.00", 10, "0.020000");

        assertEquals(SimulationStubServer.expectedFinalCapital("1000.00", "100.00", 10,
                "0.020000"), result.finalCapital());
        assertEquals("13000.00", result.contributed());
        assertEquals(2, new BigDecimal(result.finalCapital()).scale());
        assertEquals(1, stub.requests());
    }

    /** Scenario « Parametres de simulation rejetes par le moteur de calcul ». */
    @Test
    void parametresDeSimulationRejetesParLeMoteurDeCalcul() {
        assertThrows(IllegalArgumentException.class, () -> compute("99.00", "0.00", 1, "0.020000"),
                "le 400 du fournisseur reste une erreur de parametres, jamais un 5xx");
        assertEquals(1, stub.requests(), "un 400 n'est jamais reessaye");
    }

    /** Scenario « Simulation avec une hypothese de progression nulle ». */
    @Test
    void simulationAvecUneHypotheseDeProgressionNulle() {
        SimulationResult result = compute("1200.00", "100.00", 1, "0.000000");

        assertEquals("2400.00", result.finalCapital());
        assertEquals("2400.00", result.contributed());
    }

    /** Scenario « Simulation sans mensualite ». */
    @Test
    void simulationSansMensualite() {
        SimulationResult result = compute("1000.00", "0.00", 5, "0.040000");

        assertEquals("1000.00", result.contributed());
        assertEquals(SimulationStubServer.expectedFinalCapital("1000.00", "0.00", 5, "0.040000"),
                result.finalCapital());
    }
}
