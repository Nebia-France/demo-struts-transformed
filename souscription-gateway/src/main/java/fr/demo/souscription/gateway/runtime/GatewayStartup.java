package fr.demo.souscription.gateway.runtime;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GatewayStartup {

    private static final Logger LOG = Logger.getLogger(GatewayStartup.class);

    void onStart(@Observes StartupEvent ev, GatewayConfig config) {
        if (config.csrf().signingKey().isBlank()) {
            throw new IllegalStateException(
                    "gateway.csrf.signing-key (CSRF_SIGNING_KEY) must be set to a non-blank secret");
        }
        LOG.infof("gateway ready, downstreams: %s", config.summaryOfDownstreams());
    }
}
