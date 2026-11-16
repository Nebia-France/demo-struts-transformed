package fr.demo.souscription.gateway.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/**
 * Publishes the expiry date of the outbound mTLS material (plan 19, W6-T02) as
 * {@code gateway_tls_certificate_expiry_seconds{client, store}}: the {@code notAfter} of the
 * earliest-expiring certificate of each configured key store and trust store, in epoch seconds.
 *
 * <p>The stores are the ones the REST clients use ({@code quarkus.rest-client.<edge>.key-store} and
 * {@code trust-store}); they are only configured in the prod profile, so no gauge is registered in
 * dev and test. Labels are the closed set of edge names and {@code keystore}/{@code truststore}.
 * The stores are read once, on the main thread at startup, never on an event loop.
 * The alert rules compute the remaining lifetime as {@code value - time()} (see
 * {@code docs/observability.md}).
 */
@ApplicationScoped
public class CertificateExpiryMetrics {

    static final String METRIC = "gateway.tls.certificate.expiry";

    static final List<String> CLIENTS = List.of("catalogue", "simulation", "subscription");

    private static final Logger LOG = Logger.getLogger(CertificateExpiryMetrics.class);

    @Inject
    MeterRegistry registry;

    @Inject
    Config config;

    void onStart(@Observes StartupEvent event) {
        for (String client : CLIENTS) {
            register(client, "keystore", "key-store");
            register(client, "truststore", "trust-store");
        }
    }

    private void register(String client, String storeTag, String key) {
        String prefix = "quarkus.rest-client." + client + "." + key;
        Optional<String> location = config.getOptionalValue(prefix, String.class);
        if (location.isEmpty()) {
            return;
        }
        String password = config.getOptionalValue(prefix + "-password", String.class).orElse("");
        OptionalLong notAfter = earliestNotAfter(toPath(location.get()), password.toCharArray());
        if (notAfter.isEmpty()) {
            LOG.warnf("No certificate readable in %s for the %s edge", storeTag, client);
            return;
        }
        long epochSeconds = notAfter.getAsLong();
        Gauge.builder(METRIC, () -> epochSeconds)
                .description("notAfter of the earliest-expiring mTLS certificate, epoch seconds")
                .baseUnit("seconds")
                .tag("client", client)
                .tag("store", storeTag)
                .strongReference(true)
                .register(registry);
    }

    /** Earliest {@code notAfter} (epoch seconds) of the X.509 certificates of a key store file. */
    static OptionalLong earliestNotAfter(Path store, char[] password) {
        try {
            KeyStore keyStore = KeyStore.getInstance(store.toFile(), password);
            long earliest = Long.MAX_VALUE;
            for (String alias : Collections.list(keyStore.aliases())) {
                Certificate[] chain = keyStore.getCertificateChain(alias);
                if (chain == null) {
                    Certificate single = keyStore.getCertificate(alias);
                    chain = single == null ? new Certificate[0] : new Certificate[] {single};
                }
                for (Certificate certificate : chain) {
                    if (certificate instanceof X509Certificate x509) {
                        earliest = Math.min(earliest, x509.getNotAfter().toInstant().getEpochSecond());
                    }
                }
            }
            return earliest == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(earliest);
        } catch (Exception e) {
            LOG.warnf("Cannot read the mTLS store %s: %s", store, e.getClass().getSimpleName());
            return OptionalLong.empty();
        }
    }

    private static Path toPath(String location) {
        return Path.of(location.startsWith("file:") ? location.substring("file:".length()) : location);
    }
}
