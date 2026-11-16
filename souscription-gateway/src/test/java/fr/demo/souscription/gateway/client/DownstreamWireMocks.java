package fr.demo.souscription.gateway.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three downstream providers are not deployed yet, so each consumed contract is answered by a
 * WireMock server whose mappings are <strong>generated from the contract specs</strong> under
 * {@code global/contracts/} by {@code src/test/scripts/generate-stubs.sh} (never edited by hand).
 *
 * <p>Test scope only: nothing here is on the production classpath, and going live is a pure
 * environment change (see {@code docs/downstream-clients.md}).
 */
public final class DownstreamWireMocks {

    /** Config keys of the three REST clients, i.e. {@code quarkus.rest-client.<key>.url}. */
    public static final List<String> CONFIG_KEYS = List.of("catalogue", "simulation", "subscription");

    private static final Path MAPPINGS_ROOT = Path.of("src", "test", "resources", "wiremock");

    private static final Map<String, WireMockServer> SERVERS = new LinkedHashMap<>();

    private static final Map<String, Integer> PORTS = new LinkedHashMap<>();

    private static final Map<String, Tls> TLS = new LinkedHashMap<>();

    /**
     * The doubles bind, and are reached on, the IPv4 loopback address only. {@code localhost} may
     * resolve to {@code ::1} first, and a dynamic port bound on the wildcard address can be bound
     * again on another address by a concurrent process of the build host: the gateway would then
     * talk to that process instead of its double, and the suite would fail depending on what runs
     * next to it.
     */
    public static final String LOOPBACK = "127.0.0.1";

    private DownstreamWireMocks() {
    }

    /**
     * Starts one server per downstream on a free port, idempotently.
     *
     * <p>Each server binds a dynamic port itself (no reserve-then-bind race), and the port it
     * actually bound is kept for the whole run: the chaos suite shuts a downstream down to produce
     * a real {@code connection refused} and brings it back on the same port, the client URL being
     * resolved once at startup.
     *
     * @return the base URI of each downstream, keyed by REST client config key.
     */
    public static synchronized Map<String, String> start() {
        if (SERVERS.isEmpty()) {
            for (String key : CONFIG_KEYS) {
                Path root = MAPPINGS_ROOT.resolve(key);
                if (!Files.isDirectory(root.resolve("mappings"))) {
                    throw new IllegalStateException("Missing WireMock mappings for " + key + " at "
                            + root.toAbsolutePath());
                }
                WireMockServer server = listen(key, 0);
                PORTS.put(key, server.port());
                SERVERS.put(key, server);
            }
        }
        Map<String, String> baseUris = new LinkedHashMap<>();
        PORTS.forEach((key, port) -> baseUris.put(key, "http://" + LOOPBACK + ":" + port));
        return baseUris;
    }

    public static synchronized void stop() {
        SERVERS.values().forEach(WireMockServer::stop);
        SERVERS.clear();
        PORTS.clear();
        TLS.clear();
    }

    /**
     * Shuts one downstream down: until {@link #bringBack(String)} is called, a connection to its
     * port is refused — the {@code connection refused} failure mode of the chaos suite (W5-T04).
     */
    public static synchronized void shutDown(String configKey) {
        WireMockServer server = SERVERS.remove(configKey);
        if (server != null) {
            server.stop();
        }
    }

    /** Brings a downstream back on its reserved port, contract mappings reloaded. */
    public static synchronized void bringBack(String configKey) {
        if (!SERVERS.containsKey(configKey) && PORTS.containsKey(configKey)) {
            SERVERS.put(configKey, listen(configKey, PORTS.get(configKey)));
        }
    }

    /** Whether the double of this downstream is currently listening. */
    public static synchronized boolean isRunning(String configKey) {
        return SERVERS.containsKey(configKey);
    }

    /**
     * Makes one downstream also answer over TLS with the given server certificate, the plaintext
     * listener staying on its port. The HTTPS port is bound once and kept: calling this again with
     * another keystore swaps the certificate presented on the same port — the invalid-certificate
     * failure mode of the chaos suite (W5-T04).
     *
     * @return the HTTPS port of the downstream.
     */
    public static synchronized int serveOverTls(String configKey, Path keystore, String password) {
        if (!PORTS.containsKey(configKey)) {
            throw new IllegalStateException("WireMock not started for " + configKey);
        }
        shutDown(configKey);
        Tls previous = TLS.get(configKey);
        TLS.put(configKey, new Tls(previous == null ? 0 : previous.httpsPort(), keystore, password));
        WireMockServer server = listen(configKey, PORTS.get(configKey));
        SERVERS.put(configKey, server);
        TLS.put(configKey, new Tls(server.httpsPort(), keystore, password));
        return server.httpsPort();
    }

    private record Tls(int httpsPort, Path keystore, String password) {
    }

    private static WireMockServer listen(String configKey, int port) {
        WireMockConfiguration options = WireMockConfiguration.options()
                .usingFilesUnderDirectory(MAPPINGS_ROOT.resolve(configKey).toString())
                .bindAddress(LOOPBACK);
        if (port == 0) {
            options.dynamicPort();
        } else {
            options.port(port);
        }
        Tls tls = TLS.get(configKey);
        if (tls != null) {
            if (tls.httpsPort() == 0) {
                options.dynamicHttpsPort();
            } else {
                options.httpsPort(tls.httpsPort());
            }
            options.keystorePath(tls.keystore().toAbsolutePath().toString())
                    .keystoreType("PKCS12")
                    .keystorePassword(tls.password())
                    .keyManagerPassword(tls.password());
        }
        WireMockServer server = new WireMockServer(options);
        server.start();
        return server;
    }

    /** The running server of one downstream, for journal assertions. */
    public static synchronized WireMockServer server(String configKey) {
        WireMockServer server = SERVERS.get(configKey);
        if (server == null) {
            throw new IllegalStateException("WireMock not started for " + configKey);
        }
        return server;
    }
}
