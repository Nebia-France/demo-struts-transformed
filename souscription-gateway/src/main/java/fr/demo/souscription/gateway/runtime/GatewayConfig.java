package fr.demo.souscription.gateway.runtime;

import io.smallrye.config.ConfigMapping;
import java.time.Duration;
import java.util.List;

@ConfigMapping(prefix = "gateway")
public interface GatewayConfig {

    Csrf csrf();

    Security security();

    interface Csrf {
        String cookieName();

        String headerName();

        int maxTokenLength();

        /** Bounded token lifetime, equivalent to the former 20-minute session window. */
        Duration ttl();

        /**
         * HMAC key protecting the issue timestamp carried by the token. Declared without a default
         * on purpose: it must come from the environment or the secret store, and a missing value
         * must fail the startup rather than fall back to a known constant.
         */
        String signingKey();
    }

    interface Security {
        String csp();

        List<String> allowedMethods();
    }

    default String summaryOfDownstreams() {
        return "catalogue, simulation, subscription";
    }
}
