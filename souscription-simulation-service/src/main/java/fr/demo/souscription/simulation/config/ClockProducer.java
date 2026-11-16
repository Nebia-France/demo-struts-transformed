package fr.demo.souscription.simulation.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.ZoneId;

@ApplicationScoped
public class ClockProducer {

    @Produces
    @Singleton
    Clock systemClock() {
        return Clock.system(ZoneId.of("Europe/Paris"));
    }
}
