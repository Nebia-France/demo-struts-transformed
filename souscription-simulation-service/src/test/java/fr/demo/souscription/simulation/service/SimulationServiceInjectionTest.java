package fr.demo.souscription.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import fr.demo.souscription.simulation.model.Simulation;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SimulationServiceInjectionTest {

    @Inject
    SimulationService service;

    @Test
    void beanIsInjectableAndKeepsLegacyBehaviour() {
        Simulation result = service.calculate(new BigDecimal("1200"), new BigDecimal("100"), 1, BigDecimal.ZERO);
        assertNotNull(service);
        assertEquals(new BigDecimal("2400.00"), result.finalCapital());
        assertEquals(new BigDecimal("0.00"), result.gains());
    }
}
