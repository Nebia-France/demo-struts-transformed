package fr.demo.souscription.simulation.model;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import fr.demo.souscription.simulation.api.SimulationRequest;
import fr.demo.souscription.simulation.api.SimulationResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity skeleton for 08-simulation-domain-record-and-dtos.
 *
 * Two concerns: the record conversion must keep gains() as a DERIVED accessor (never a component),
 * and the DTO component names are the frozen wire contract
 * (global/contracts/shared-schemas/SimulationComputeRequest.json and SimulationResult.json).
 *
 * Copy to souscription-simulation-service/src/test/java/fr/demo/souscription/simulation/model/.
 */
class DomainRecordDtoParityTest {

    @Test
    void simulationIsARecordWithTheFiveLegacyComponents() {
        assertTrue(Simulation.class.isRecord(), "Simulation must be a record");
        assertEquals(List.of("initial", "monthly", "years", "contributed", "finalCapital"),
                componentNames(Simulation.class),
                "the record must keep the legacy DOMAIN names; the wire names belong to the DTOs");
    }

    @Test
    void serializableResidueIsGone() {
        assertFalse(java.io.Serializable.class.isAssignableFrom(Simulation.class),
                "Serializable existed only for the HttpSession storage the target does not have");
    }

    /** gains() is derived, so it can never disagree with finalCapital - contributed. */
    @Test
    void gainsIsDerivedAndNotAComponent() {
        assertFalse(componentNames(Simulation.class).contains("gains"),
                "gains must not be a stored record component");
        Simulation s = new Simulation(new BigDecimal("1200.00"), new BigDecimal("100.00"), 1,
                new BigDecimal("2400.00"), new BigDecimal("2400.00"));
        assertEquals(new BigDecimal("0.00"), s.gains());

        Simulation grown = new Simulation(new BigDecimal("1000.00"), new BigDecimal("100.00"), 5,
                new BigDecimal("7000.00"), new BigDecimal("7762.49"));
        assertEquals(new BigDecimal("762.49"), grown.gains());
    }

    /** Request component names are the frozen contract of SimulationComputeRequest.json. */
    @Test
    void requestDtoMatchesTheFrozenSchemaNames() {
        assertEquals(List.of("initialAmount", "monthlyAmount", "durationYears", "annualRate"),
                componentNames(SimulationRequest.class));
    }

    /** Response component names are the frozen contract of SimulationResult.json; no gains on the wire. */
    @Test
    void responseDtoMatchesTheFrozenSchemaNames() {
        assertEquals(List.of("initialAmount", "monthlyAmount", "durationYears", "contributed", "finalCapital"),
                componentNames(SimulationResponse.class));
        assertFalse(componentNames(SimulationResponse.class).contains("gains"),
                "gains is absent from SimulationResult.json; adding it is an unannounced contract extension");
    }

    @Test
    void monetaryFieldsAreBigDecimalAndDurationIsAnInt() {
        assertEquals(BigDecimal.class, componentType(SimulationResponse.class, "initialAmount"));
        assertEquals(BigDecimal.class, componentType(SimulationResponse.class, "monthlyAmount"));
        assertEquals(BigDecimal.class, componentType(SimulationResponse.class, "contributed"));
        assertEquals(BigDecimal.class, componentType(SimulationResponse.class, "finalCapital"));
        assertEquals(int.class, componentType(SimulationResponse.class, "durationYears"));
        assertEquals(BigDecimal.class, componentType(SimulationRequest.class, "annualRate"));
    }

    /**
     * Mapping must preserve the value AND the scale. Adapt the mapper call to the signature plan 08
     * introduces (a static factory on SimulationResponse, or a dedicated mapper bean).
     */
    @Test
    void mappingPreservesValueAndScale() {
        Simulation domain = new Simulation(new BigDecimal("1200.00"), new BigDecimal("100.00"), 1,
                new BigDecimal("2400.00"), new BigDecimal("2400.00"));
        SimulationResponse response = SimulationResponse.from(domain);

        assertEquals(new BigDecimal("1200.00"), response.initialAmount());
        assertEquals(new BigDecimal("100.00"), response.monthlyAmount());
        assertEquals(1, response.durationYears());
        assertEquals(new BigDecimal("2400.00"), response.contributed());
        assertEquals(new BigDecimal("2400.00"), response.finalCapital());

        assertEquals(2, response.initialAmount().scale());
        assertEquals(2, response.monthlyAmount().scale());
        assertEquals(2, response.contributed().scale());
        assertEquals(2, response.finalCapital().scale());
    }

    private static List<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(c -> c.getName())
                .collect(Collectors.toList());
    }

    private static Class<?> componentType(Class<?> type, String name) {
        return Arrays.stream(type.getRecordComponents())
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing record component: " + name))
                .getType();
    }

    /** Guards against a @JsonProperty alias reintroducing a second accepted spelling. */
    @Test
    void noJsonPropertyAliasOnTheDtos() {
        for (Method accessor : SimulationResponse.class.getDeclaredMethods()) {
            assertFalse(Arrays.stream(accessor.getAnnotations())
                            .anyMatch(a -> a.annotationType().getSimpleName().equals("JsonProperty")),
                    "no @JsonProperty alias: the published OpenAPI must describe exactly one spelling");
        }
    }
}
