package fr.demo.souscription.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.ZoneId;

/**
 * Unique fabrique CDI de l'horloge (W3-T04).
 *
 * <p>Remplace le {@code Clock clock = Clock.system(ZoneId.of("Europe/Paris"))} construit a la
 * main dans le bootstrap servlet {@code web/Application} (initialisation du contexte web). Le fuseau n'est pas decoratif : la regle
 * de majorite a 18 ans evalue {@code LocalDate.now(clock)}, et un {@code Clock.systemUTC()}
 * decalerait la frontiere de majorite d'un jour pendant une partie de la journee.
 */
@ApplicationScoped
public class TimeProducer {

    @Produces @Singleton Clock clock() {
        return Clock.system(ZoneId.of("Europe/Paris"));
    }
}
