package fr.demo.souscription.simulation.security;

import io.quarkus.test.junit.callback.QuarkusTestBeforeEachCallback;
import io.quarkus.test.junit.callback.QuarkusTestMethodContext;
import io.restassured.RestAssured;
import io.restassured.config.SSLConfig;
import io.restassured.specification.RequestSpecification;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Makes the whole test suite speak the production transport (W6-T01).
 *
 * <p>The service accepts nothing but TLS 1.3 with {@code client-auth=required}, in test exactly as
 * in production: {@code quarkus.http.insecure-requests=disabled} leaves no plaintext listener to
 * fall back to. Every suite that calls the traffic port therefore has to present a client
 * certificate, so this callback configures RestAssured globally instead of each of the forty-odd
 * test classes repeating the same SSL setup and drifting from it.
 *
 * <p>It is registered as a {@link QuarkusTestBeforeEachCallback} through
 * {@code META-INF/services}, not as a JUnit extension: {@code @QuarkusTest} classes run in the
 * Quarkus deployment classloader, so a JUnit-level extension would configure a different copy of the
 * {@code RestAssured} class than the one the tests use and silently leave the handshake
 * unauthenticated. It runs after Quarkus has pointed RestAssured at the test SSL port, and only adds
 * the SSL material.
 *
 * <p>The material comes from {@code ../target/dev-certs}, generated at every build by
 * {@code .ci/generate-dev-certs.sh}: throwaway self-signed identities that live in the reactor build
 * directory, never in the module and never in the image. The default identity is the contracted
 * {@code souscription-gateway} one; {@link #rogueIdentity()} presents a PKI-valid but uncontracted
 * identity, which is what proves the allow-list rejects it.
 */
public final class MutualTlsHarness implements QuarkusTestBeforeEachCallback {

    /** Throwaway passphrase of the generated PKCS#12 stores (see .ci/generate-dev-certs.sh). */
    private static final String STORE_SECRET = "devstore";
    private static final Path CERT_DIR = Path.of("..", "target", "dev-certs");

    public static final String GATEWAY_IDENTITY =
            "spiffe://horizon-souscription/ns/horizon/sa/souscription-gateway";
    public static final String ROGUE_IDENTITY =
            "spiffe://horizon-souscription/ns/horizon/sa/souscription-web";

    @Override
    public void beforeEach(QuarkusTestMethodContext context) {
        RestAssured.config = RestAssured.config().sslConfig(sslConfig("client"));
    }

    /** A request specification presenting the uncontracted souscription-web identity. */
    public static RequestSpecification rogueIdentity() {
        return RestAssured.given().config(RestAssured.config().sslConfig(sslConfig("rogue")));
    }

    /** True when the build-time certificates are present; false in a cert-less environment. */
    public static boolean certificatesAvailable() {
        return Files.isReadable(CERT_DIR.resolve("client-keystore.p12"));
    }

    /** The traffic-port base URI: https only, the plaintext listener does not exist. */
    public static String trafficBaseUri() {
        return "https://localhost:" + RestAssured.port;
    }

    /**
     * A JDK {@link HttpClient} presenting the contracted gateway identity, for the suites that drive
     * concurrent bursts directly instead of through RestAssured.
     */
    public static HttpClient httpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .sslContext(sslContext("client"))
                .build();
    }

    /**
     * An {@link SSLContext} that trusts the service but holds no client certificate: the transport
     * conformance test uses it to prove that an unauthenticated caller is rejected in the handshake
     * and not by the application.
     */
    public static SSLContext serverTrustOnlyContext() {
        return sslContext(null);
    }

    private static SSLContext sslContext(String identity) {
        try {
            KeyManagerFactory keyManagers = null;
            if (identity != null) {
                keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(load(identity + "-keystore.p12"), STORE_SECRET.toCharArray());
            }
            TrustManagerFactory trustManagers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(load("truststore.p12"));
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(keyManagers == null ? null : keyManagers.getKeyManagers(),
                    trustManagers.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException(
                    "build-time mTLS material missing or unreadable: run .ci/generate-dev-certs.sh", e);
        }
    }

    private static KeyStore load(String name) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store(name).toPath())) {
            store.load(in, STORE_SECRET.toCharArray());
        }
        return store;
    }

    private static SSLConfig sslConfig(String identity) {
        return SSLConfig.sslConfig()
                .keyStore(store(identity + "-keystore.p12"), STORE_SECRET)
                .keystoreType("PKCS12")
                .trustStore(store("truststore.p12"), STORE_SECRET)
                .trustStoreType("PKCS12")
                .allowAllHostnames();
    }

    /** Absolute file: a relative path would be looked up on the classpath, where it is not. */
    private static File store(String name) {
        return CERT_DIR.resolve(name).toAbsolutePath().normalize().toFile();
    }
}
