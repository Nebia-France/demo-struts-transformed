package features.step_definitions;

import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import io.restassured.RestAssured;
import io.restassured.response.Response;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Harnais d'execution de la suite Cucumber catalogue (W7-T02).
 *
 * <p>Deux modes, selon les proprietes systeme fournies :
 * <ul>
 *   <li><b>local</b> (defaut) : le fast-jar produit par la phase {@code package} est demarre en
 *       profil {@code test} (port libre, ecoute en clair), puis arrete en fin de suite. Aucun port
 *       n'est code en dur.</li>
 *   <li><b>endpoint mTLS</b> : {@code -Dcatalogue.base-uri=https://host:8443} cible une instance
 *       deja demarree ; {@code -Dcatalogue.client-cert} / {@code -Dcatalogue.client-key} fournissent
 *       le materiel cryptographique client exige par {@code quarkus.http.ssl.client-auth=required}.</li>
 * </ul>
 */
public class CatalogueWorld {

    private static final String BASE_URI_PROPERTY = "catalogue.base-uri";
    private static final String CLIENT_CERT_PROPERTY = "catalogue.client-cert";
    private static final String CLIENT_KEY_PROPERTY = "catalogue.client-key";
    private static final Path FAST_JAR = Path.of("target", "quarkus-app", "quarkus-run.jar");

    private static Process serveur;

    /** Reponse HTTP de la derniere etape {@code When}, partagee avec {@link CommonSteps}. */
    static Response lastResponse;

    /** Exception capturee par les etapes de niveau service (liste non modifiable). */
    static RuntimeException lastException;

    @BeforeAll
    public static void demarrerLeCatalogue() throws Exception {
        String baseUri = System.getProperty(BASE_URI_PROPERTY);
        if (baseUri != null && !baseUri.isBlank()) {
            RestAssured.baseURI = baseUri;
            configurerMaterielClient();
        } else {
            int port = portLibre();
            serveur = demarrerFastJar(port);
            RestAssured.baseURI = "http://localhost:" + port;
            attendreDisponibilite(RestAssured.baseURI + "/v1/products");
        }
    }

    @AfterAll
    public static void arreterLeCatalogue() {
        if (serveur != null) {
            serveur.destroy();
            try {
                if (!serveur.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    serveur.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                serveur.destroyForcibly();
            }
            serveur = null;
        }
        RestAssured.reset();
    }

    @Before
    public void reinitialiserLeContexte() {
        lastResponse = null;
        lastException = null;
    }

    @After
    public void oublierLeContexte() {
        lastResponse = null;
        lastException = null;
    }

    static Response reponse() {
        if (lastResponse == null) {
            throw new IllegalStateException("Aucune reponse HTTP : l'etape When n'a pas ete executee");
        }
        return lastResponse;
    }

    private static Process demarrerFastJar(int port) throws IOException {
        if (!Files.isRegularFile(FAST_JAR)) {
            throw new IllegalStateException("fast-jar absent (" + FAST_JAR.toAbsolutePath()
                    + ") : la suite Cucumber s'execute apres la phase package");
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(List.of(java,
                "-Dquarkus.profile=test",
                "-Dquarkus.http.port=" + port,
                "-Dquarkus.management.port=" + portLibre(),
                "-jar", FAST_JAR.toString()));
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(
                Path.of("target", "cucumber-catalogue-server.log").toFile()));
        return builder.start();
    }

    private static void attendreDisponibilite(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(2)).GET().build();
        Exception derniere = null;
        for (int tentative = 0; tentative < 120; tentative++) {
            if (serveur != null && !serveur.isAlive()) {
                throw new IllegalStateException(
                        "Le catalogue s'est arrete au demarrage, voir target/cucumber-catalogue-server.log");
            }
            try {
                HttpResponse<Void> response =
                        client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                derniere = e;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Le catalogue n'a pas repondu sur " + url, derniere);
    }

    private static int portLibre() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Rejoue la suite contre un endpoint mTLS : le certificat et la cle client (PEM, cle au format
     * PKCS#8) sont charges dans un keystore en memoire et presentes a chaque requete.
     */
    private static void configurerMaterielClient() throws Exception {
        String cert = System.getProperty(CLIENT_CERT_PROPERTY);
        String key = System.getProperty(CLIENT_KEY_PROPERTY);
        if (cert == null || cert.isBlank() || key == null || key.isBlank()) {
            return;
        }
        char[] motDePasse = "changeit".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, motDePasse);
        try (InputStream in = Files.newInputStream(Path.of(cert))) {
            Certificate[] chaine = CertificateFactory.getInstance("X.509")
                    .generateCertificates(in).toArray(new Certificate[0]);
            keyStore.setKeyEntry("client", clePrivee(Path.of(key)), motDePasse, chaine);
        }
        Path pkcs12 = Files.createTempFile("catalogue-client", ".p12");
        try (java.io.OutputStream out = Files.newOutputStream(pkcs12)) {
            keyStore.store(out, motDePasse);
        }
        pkcs12.toFile().deleteOnExit();
        RestAssured.config = RestAssured.config().sslConfig(
                RestAssured.config().getSSLConfig()
                        .keyStore(pkcs12.toFile(), new String(motDePasse))
                        .relaxedHTTPSValidation("TLSv1.3"));
    }

    private static PrivateKey clePrivee(Path pem) throws Exception {
        String contenu = Files.readString(pem, StandardCharsets.UTF_8)
                .replaceAll("-----(BEGIN|END)[^-]*-----", "")
                .replaceAll("\\s", "");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(contenu));
        for (String algorithme : List.of("RSA", "EC")) {
            try {
                return KeyFactory.getInstance(algorithme).generatePrivate(spec);
            } catch (java.security.spec.InvalidKeySpecException ignored) {
                // algorithme suivant
            }
        }
        throw new IllegalArgumentException(
                "Cle client illisible (PKCS#8 RSA ou EC attendu) : " + pem);
    }
}
