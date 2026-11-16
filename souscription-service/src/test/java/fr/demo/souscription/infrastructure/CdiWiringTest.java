package fr.demo.souscription.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import fr.demo.souscription.application.ConfirmationService;
import fr.demo.souscription.application.DraftService;
import fr.demo.souscription.integration.CatalogueLookup;
import fr.demo.souscription.integration.SimulationLookup;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * W3-T02 / W3-T03 / W3-T04 : le graphe d'objets est resolu par ArC et plus par le
 * service-locator du contexte web source, et l'horloge est produite par
 * {@link TimeProducer} sur le fuseau Europe/Paris.
 */
@QuarkusTest
class CdiWiringTest {

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
    void allBeansResolveFromTheContainer() {
        assertNotNull(confirmations);
        assertNotNull(drafts);
        assertNotNull(clock);
        assertNotNull(catalogue);
        assertNotNull(simulations);
    }

    @Test
    void clockRunsOnEuropeParis() {
        assertEquals(ZoneId.of("Europe/Paris"), clock.getZone());
    }
}
