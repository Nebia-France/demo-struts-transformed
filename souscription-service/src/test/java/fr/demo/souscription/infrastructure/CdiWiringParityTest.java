package fr.demo.souscription.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.SimulationResult;
import fr.demo.souscription.application.ConfirmationService;
import fr.demo.souscription.application.DraftService;
import fr.demo.souscription.integration.CatalogueLookup;
import fr.demo.souscription.integration.ContractStubsResource;
import fr.demo.souscription.integration.SimulationComputeRequest;
import fr.demo.souscription.integration.SimulationLookup;
import fr.demo.souscription.integration.SimulationRejectedException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Parity skeleton for 15-souscription-service-cdi-arc-wiring.
 *
 * Baseline references:
 *  - BASELINE.md 4.5/4.6 — InputValidator and SimulationService, both built with
 *    Clock.system(ZoneId.of("Europe/Paris")) in web/Application.java line 46.
 *  - BASELINE.md 4.8 — CatalogService and SimulationService are reallocated to sibling services.
 *    Since plan 41 the local stubs are deleted: the source catalogue and arithmetic are asserted
 *    through the remote REST clients, served by the contract-derived stubs (ContractStubsResource).
 *
 * Install at:
 *   souscription-service/src/test/java/fr/demo/souscription/infrastructure/CdiWiringParityTest.java
 */
@QuarkusTest
@TestProfile(CdiWiringParityTest.RemoteClientsWithStubsProfile.class)
class CdiWiringParityTest {

    /** Both remote clients pointed at the contract-derived stubs, started for the profile. */
    public static class RemoteClientsWithStubsProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.rest-client.catalogue.url", "http://localhost:18089",
                    "quarkus.rest-client.simulation.url", "http://localhost:18090");
        }

        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(ContractStubsResource.class));
        }
    }

    @Inject
    ConfirmationService confirmations;

    @Inject
    DraftService drafts;

    @Inject
    Clock clock;

    @Inject
    CatalogueLookup catalogue;

    @Inject
    SimulationLookup simulations;

    @Test
    void containerResolvesTheWholeGraph() {
        assertTrue(confirmations != null && drafts != null && catalogue != null
                && simulations != null, "the five beans of the source object graph must resolve");
    }

    @Test
    void clockZoneIsEuropeParis() {
        assertEquals(ZoneId.of("Europe/Paris"), clock.getZone(),
                "the source used Clock.system(ZoneId.of(\"Europe/Paris\")). With systemUTC, a user "
                + "turning 18 today and submitting at 00:30 Paris time is still 17 in UTC: the "
                + "majority verdict flips for part of every day");
    }

    @Test
    void noFinalMethodOnScopedBeans() {
        for (Class<?> type : List.of(ConfirmationService.class, DraftService.class)) {
            assertFalse(Modifier.isFinal(type.getModifiers()),
                    type.getSimpleName() + " must not be final: ArC cannot proxy it");
            for (var method : type.getDeclaredMethods()) {
                assertFalse(Modifier.isFinal(method.getModifiers()),
                        type.getSimpleName() + "." + method.getName() + " must not be final: "
                        + "@Transactional interception is SILENTLY skipped on a final method, so "
                        + "plan 23's confirmation would run without a transaction");
            }
        }
    }

    @Test
    void remoteSimulationReproducesSourceArithmetic() {
        SimulationResult result = simulations.compute(new SimulationComputeRequest(
                "1000.00", "100.00", 10, "0.020000"));

        BigDecimal expectedCapital = sourceAlgorithm(new BigDecimal("1000.00"),
                new BigDecimal("100.00"), 10, new BigDecimal("0.020000"));
        assertEquals(expectedCapital.toPlainString(), result.finalCapital(),
                "the remote simulation must reproduce the source loop exactly: monthly compounding with "
                + "MathContext.DECIMAL128 and setScale(2, HALF_UP). A closed-form Math.pow or a "
                + "double differs in the last cents, and every test between plans 15 and 28 is "
                + "written against these numbers");
        assertEquals("13000.00", result.contributed(),
                "contributed = initial + monthly * years * 12, at scale 2");
    }

    @Test
    void remoteSimulationKeepsSourceGuards() {
        assertThrows(SimulationRejectedException.class,
                () -> simulations.compute(new SimulationComputeRequest("99.99", "100.00", 10, "0.020000")),
                "initial below 100 was rejected by SimulationService");
        assertThrows(SimulationRejectedException.class,
                () -> simulations.compute(new SimulationComputeRequest("1000.00", "2000.01", 10, "0.020000")),
                "monthly above 2000 was rejected");
        assertThrows(SimulationRejectedException.class,
                () -> simulations.compute(new SimulationComputeRequest("1000.00", "100.00", 31, "0.020000")),
                "years above 30 was rejected");
        assertThrows(SimulationRejectedException.class,
                () -> simulations.compute(new SimulationComputeRequest("1000.00", "100.00", 0, "0.020000")),
                "years below 1 was rejected");
        assertThrows(SimulationRejectedException.class,
                () -> simulations.compute(new SimulationComputeRequest("1000.00", "100.00", 10, "1.500000")),
                "a rate above 1 was rejected");
    }

    @Test
    void remoteCatalogueReproducesSourceLookup() {
        for (String code : List.of("PRUDENT", "DYNAMIQUE")) {
            assertTrue(catalogue.findProduct(code).isPresent(),
                    "the source products must be resolved through the remote catalogue: " + code);
        }
        // CatalogService.find used equals: the lookup is case- and whitespace-sensitive.
        assertTrue(catalogue.findProduct("prudent").isEmpty(), "lowercase must miss (source: equals)");
        assertTrue(catalogue.findProduct(" PRUDENT ").isEmpty(), "padded code must miss — no trim()");
        assertTrue(catalogue.findProduct("PRUDENT123").isEmpty(), "suffixed code must miss");
        assertTrue(catalogue.findProduct(null).isEmpty(),
                "find(null) returned no product and never threw (source: CatalogAction)");
    }

    /** The source loop, reproduced here as the reference oracle (service/SimulationService.java). */
    private static BigDecimal sourceAlgorithm(BigDecimal initial, BigDecimal monthly, int years,
                                              BigDecimal annualRate) {
        MathContext precision = MathContext.DECIMAL128;
        BigDecimal factor = BigDecimal.ONE.add(annualRate.divide(new BigDecimal("12"), precision));
        BigDecimal capital = initial;
        for (int month = 0; month < years * 12; month++) {
            capital = capital.multiply(factor, precision).add(monthly, precision);
        }
        return capital.setScale(2, RoundingMode.HALF_UP);
    }
}
