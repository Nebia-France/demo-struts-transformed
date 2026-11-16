package fr.demo.souscription.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Negative and positive checks of the outbound mTLS posture (W4-T03).
 *
 * <p>The three downstream edges are mutually authenticated: the gateway presents a client
 * certificate and verifies the server certificate <em>and its host name</em>. This test locks the
 * two failure modes that silently disable that posture — calling without a client certificate, and
 * accepting a server certificate issued for another host — plus the configuration itself, because
 * {@code verify-host} set to {@code false} "to unblock dev" is the most common regression.
 *
 * <p>All key material is self-signed and generated under {@code target/} at test start by
 * {@code keytool}: no secret is ever committed (see {@code docs/mtls.md}).
 */
class MtlsTest {

    private static final String PASSWORD = "test-only-mtls-password";
    private static final Path MATERIAL = Path.of("target", "mtls-test");

    private static Path serverKeystore;
    private static Path serverTruststore;
    private static Path clientKeystore;
    private static Path clientTruststore;
    private static Path mismatchedServerKeystore;
    private static Path mismatchedTruststore;

    private static WireMockServer server;
    private static WireMockServer mismatchedHostServer;

    @BeforeAll
    static void generateMaterialAndStartServers() throws Exception {
        Files.createDirectories(MATERIAL);
        serverKeystore = keyPair("server", "CN=localhost", "SAN=dns:localhost");
        clientKeystore = keyPair("client", "CN=souscription-gateway", null);
        // Certificat serveur volontairement emis pour un autre nom d'hote.
        mismatchedServerKeystore = keyPair("mismatch", "CN=autre-hote", "SAN=dns:autre-hote");

        clientTruststore = truststore("client-truststore", serverKeystore, "server");
        serverTruststore = truststore("server-truststore", clientKeystore, "client");
        mismatchedTruststore = truststore("mismatch-truststore", mismatchedServerKeystore, "mismatch");

        server = startHttpsServer(serverKeystore);
        mismatchedHostServer = startHttpsServer(mismatchedServerKeystore);
    }

    @AfterAll
    static void stopServers() {
        if (server != null) {
            server.stop();
        }
        if (mismatchedHostServer != null) {
            mismatchedHostServer.stop();
        }
    }

    @Test
    void callWithoutClientCertificateIsRefused() throws Exception {
        HttpClient withoutKeystore = client(null, clientTruststore);

        // Le serveur exige un certificat client : la poignee de main echoue, aucune reponse HTTP.
        assertThrows(IOException.class, () -> send(withoutKeystore, "localhost", server.httpsPort()));
    }

    @Test
    void callWithClientCertificateIsAccepted() throws Exception {
        HttpClient withKeystore = client(clientKeystore, clientTruststore);

        HttpResponse<String> response = send(withKeystore, "localhost", server.httpsPort());

        assertEquals(200, response.statusCode());
    }

    @Test
    void mismatchedServerHostFailsWhileHostVerificationIsOn() throws Exception {
        // Le certificat du serveur est pleinement approuve (il est dans le truststore) : le seul
        // motif d'echec possible est la verification du nom d'hote.
        HttpClient trustingClient = client(clientKeystore, mismatchedTruststore);

        SSLException failure = assertThrows(SSLException.class,
                () -> send(trustingClient, "localhost", mismatchedHostServer.httpsPort()));

        assertTrue(rootCause(failure).toLowerCase().contains("autre-hote")
                        || rootCause(failure).toLowerCase().contains("subject alternative"),
                "Expected a host verification failure, got: " + rootCause(failure));
    }

    @Test
    void hostVerificationIsEnabledOnTheThreeClientsAndNeverDisabled() throws Exception {
        List<String> lines = Files.readAllLines(
                Path.of("src", "main", "resources", "application.properties"), StandardCharsets.UTF_8);

        for (String configKey : DownstreamWireMocks.CONFIG_KEYS) {
            String expected = "quarkus.rest-client." + configKey + ".verify-host=true";
            assertTrue(lines.stream().anyMatch(line -> line.strip().equals(expected)),
                    "Missing " + expected);
        }
        String disabled = "verify-host=" + "false";
        assertFalse(lines.stream().anyMatch(line -> line.contains(disabled)),
                "Host verification must never be disabled, not even in dev");
    }

    private static HttpResponse<String> send(HttpClient httpClient, String host, int port)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://" + host + ":" + port + "/v1/ping"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient client(Path keystore, Path truststore) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        KeyManagerFactory keyManagers = null;
        if (keystore != null) {
            keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(load(keystore), PASSWORD.toCharArray());
        }
        TrustManagerFactory trustManagers =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(load(truststore));
        context.init(keyManagers == null ? null : keyManagers.getKeyManagers(),
                trustManagers.getTrustManagers(), null);
        // java.net.http verifie le nom d'hote du certificat serveur par defaut : c'est l'equivalent
        // client de quarkus.rest-client.<key>.verify-host=true.
        return HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(5)).build();
    }

    private static KeyStore load(Path p12) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p12)) {
            store.load(in, PASSWORD.toCharArray());
        }
        return store;
    }

    private static WireMockServer startHttpsServer(Path keystore) {
        WireMockServer https = new WireMockServer(WireMockConfiguration.options()
                .httpDisabled(true)
                .dynamicHttpsPort()
                .keystorePath(keystore.toAbsolutePath().toString())
                .keystoreType("PKCS12")
                .keystorePassword(PASSWORD)
                .keyManagerPassword(PASSWORD)
                .needClientAuth(true)
                .trustStorePath(serverTruststore.toAbsolutePath().toString())
                .trustStoreType("PKCS12")
                .trustStorePassword(PASSWORD));
        https.start();
        https.stubFor(get(urlEqualTo("/v1/ping")).willReturn(aResponse().withStatus(200)));
        return https;
    }

    private static Path keyPair(String alias, String dname, String san) throws Exception {
        Path keystore = MATERIAL.resolve(alias + "-keystore.p12");
        Files.deleteIfExists(keystore);
        List<String> command = new java.util.ArrayList<>(List.of(
                keytool(), "-genkeypair",
                "-alias", alias,
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", dname,
                "-storetype", "PKCS12",
                "-keystore", keystore.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD));
        if (san != null) {
            command.addAll(List.of("-ext", san));
        }
        run(command);
        return keystore;
    }

    private static Path truststore(String name, Path sourceKeystore, String alias) throws Exception {
        Path certificate = MATERIAL.resolve(alias + ".cer");
        Path truststore = MATERIAL.resolve(name + ".p12");
        Files.deleteIfExists(certificate);
        Files.deleteIfExists(truststore);
        run(List.of(keytool(), "-exportcert", "-alias", alias,
                "-keystore", sourceKeystore.toString(), "-storetype", "PKCS12",
                "-storepass", PASSWORD, "-file", certificate.toString()));
        run(List.of(keytool(), "-importcert", "-noprompt", "-alias", alias,
                "-file", certificate.toString(), "-storetype", "PKCS12",
                "-keystore", truststore.toString(), "-storepass", PASSWORD));
        return truststore;
    }

    private static String keytool() {
        return Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    }

    private static void run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
    }

    private static String rootCause(Throwable throwable) {
        Throwable current = throwable;
        StringBuilder chain = new StringBuilder();
        while (current != null) {
            chain.append(current).append(" | ");
            current = current.getCause();
        }
        return chain.toString();
    }
}
