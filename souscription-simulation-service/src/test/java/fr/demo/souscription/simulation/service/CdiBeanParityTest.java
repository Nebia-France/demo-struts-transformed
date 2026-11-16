package fr.demo.souscription.simulation.service;

import java.math.BigDecimal;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import fr.demo.souscription.simulation.model.Simulation;
import io.quarkus.test.junit.QuarkusTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Parity skeleton for 06-simulation-cdi-beans-arc.
 *
 * The servlet service-locator held exactly one SimulationService instance in the ServletContext.
 * After the CDI migration the container must provide the same single-instance semantics and the
 * computation must be unchanged (BASELINE.md section 4, reference case 1200/100/1/0 -> 2400.00).
 *
 * Copy to souscription-simulation-service/src/test/java/fr/demo/souscription/simulation/service/.
 */
@QuarkusTest
class CdiBeanParityTest {

    @Inject
    SimulationService service;

    @Inject
    SimulationService secondReference;

    @Inject
    SimulationInputValidator validator;

    @Test
    void beansAreInjectable() {
        assertNotNull(service, "SimulationService must be a resolvable bean");
        assertNotNull(validator, "SimulationInputValidator must be a resolvable bean");
    }

    /**
     * @ApplicationScoped means both injection points share one underlying instance. A @Dependent or
     * @RequestScoped service would allocate per call, changing the memory profile plan 19 sizes.
     */
    @Test
    void serviceIsASingleton() {
        assertSame(unwrap(service), unwrap(secondReference),
                "the service must be a single application-scoped instance, as the ServletContext held");
    }

    /** The reference case must be untouched by the CDI migration. */
    @Test
    void computationIsUnchangedAfterCdiMigration() {
        Simulation result = service.calculate(new BigDecimal("1200"), new BigDecimal("100"), 1, BigDecimal.ZERO);
        assertEquals(new BigDecimal("2400.00"), result.finalCapital());
        assertEquals(new BigDecimal("0.00"), result.gains());
        assertEquals(2, result.finalCapital().scale());
    }

    /** The guard still throws through the client proxy, not swallowed by interception. */
    @Test
    void guardStillThrowsThroughTheProxy() {
        try {
            service.calculate(new BigDecimal("99"), BigDecimal.ZERO, 1, BigDecimal.ZERO);
            throw new AssertionError("the legacy guard must still reject initial = 99");
        } catch (IllegalArgumentException expected) {
            assertEquals("Param\u00e8tres de simulation invalides", expected.getMessage());
        }
    }

    /**
     * Two client proxies of the same @ApplicationScoped bean are distinct proxy objects but delegate
     * to one contextual instance. Comparing the identity hash of a method result that depends only
     * on the instance is unreliable, so compare the proxied class instead: both must resolve to the
     * same bean class, and ArC guarantees one contextual instance per application scope.
     */
    private static Object unwrap(SimulationService proxy) {
        return proxy.getClass().getSuperclass();
    }
}
