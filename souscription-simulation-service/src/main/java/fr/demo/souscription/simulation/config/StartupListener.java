package fr.demo.souscription.simulation.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

@ApplicationScoped
public class StartupListener {
    private static final Logger LOG = Logger.getLogger(StartupListener.class);

    void onStart(@Observes StartupEvent event) {
        LOG.info("souscription-simulation-service prêt (aucune persistance, aucun état)");
    }
}
