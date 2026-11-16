package fr.demo.souscription.simulation.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ClockProducerTest {

    @Inject
    Clock clock;

    @Test
    void producesParisClock() {
        assertEquals(ZoneId.of("Europe/Paris"), clock.getZone());
    }
}
