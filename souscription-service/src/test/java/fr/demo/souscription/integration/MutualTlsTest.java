package fr.demo.souscription.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PfxOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * mTLS sortant du maillage (W6-T03).
 *
 * <p>Les deux clients REST de ce service (catalogue, simulation) parlent a des services freres
 * internes au cluster dont les {@code security-policy.json} imposent
 * {@code mechanism: mTLS}, {@code mutual: true} et {@code minimumVersion: 1.3}. La configuration
 * TLS nommee {@code mesh} de {@code application.properties} porte le certificat de charge de
 * travail et l'autorite du cluster ; ce test verifie le comportement qu'elle garantit face a un
 * serveur qui exige un certificat client.
 *
 * <p>Deux affirmations, qui sont les deux moities de l'exigence :
 * <ul>
 *   <li>sans certificat client valide, l'appel est <b>refuse</b> au niveau du transport — la
 *       confiance dans le serveur ne suffit pas ;</li>
 *   <li>avec le certificat de charge de travail, l'appel <b>aboutit</b>.</li>
 * </ul>
 *
 * <p>Le materiel X.509 est genere a l'execution dans le repertoire temporaire du systeme et
 * n'existe nulle part dans le depot ni dans l'image : aucune cle privee et aucun magasin de cles
 * n'est versionne (la PKI du cluster emet et rotate les vrais certificats).
 * Le test n'est pas un {@code @QuarkusTest} : il n'a pas besoin de l'application, et les cles
 * {@code mesh} sont prefixees {@code %prod} precisement pour que le profil de test, qui pointe
 * sur des bouchons HTTP locaux, n'exige aucun materiel TLS.
 */
class MutualTlsTest {

    private static final Path PROPERTIES = Path.of("src", "main", "resources",
            "application.properties");

    private static Vertx vertx;
    private static Path keystore;
    private static String password;
    private static HttpServer server;
    private static int port;

    /** Magasin PKCS12 ephemere : cle et certificat de la charge de travail du test. */
    private static PfxOptions workload() {
        return new PfxOptions().setPath(keystore.toString()).setPassword(password);
    }

    @BeforeAll
    static void startServerRequiringAClientCertificate() throws Exception {
        // Materiel genere par keytool dans le repertoire temporaire du systeme, jamais sous
        // souscription-service/ : ni le depot ni l'image ne contiennent de cle. Le mot de passe
        // est tire au hasard a chaque execution — il ne protege qu'un fichier ephemere.
        password = UUID.randomUUID().toString();
        keystore = Files.createTempDirectory("horizon-mtls-test").resolve("workload.keystore");
        int keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "workload", "-keyalg", "RSA", "-keysize", "2048",
                "-sigalg", "SHA256withRSA", "-validity", "1",
                "-dname", "CN=localhost,OU=souscription-service,O=horizon",
                "-ext", "SAN=dns:localhost",
                "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass", password, "-keypass", password)
                .redirectErrorStream(true)
                .start()
                .waitFor();
        if (keytool != 0) {
            throw new IllegalStateException("keytool a echoue, code " + keytool);
        }

        vertx = Vertx.vertx();
        server = vertx.createHttpServer(new HttpServerOptions()
                        .setSsl(true)
                        .setPfxKeyCertOptions(workload())
                        // L'autorite de confiance du serveur : seul un pair porteur de ce
                        // certificat est admis.
                        .setPfxTrustOptions(workload())
                        // REQUIRED et non REQUEST : un pair sans certificat est rejete pendant la
                        // poignee de main. Avec REQUEST, l'absence de certificat passerait et le
                        // test ne prouverait rien.
                        .setClientAuth(ClientAuth.REQUIRED)
                        .setPort(0)
                        .setHost("localhost"))
                .requestHandler(request -> request.response()
                        .putHeader("content-type", "application/json")
                        .end("{\"code\":\"PRUDENT\"}"))
                .listen()
                .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        port = server.actualPort();
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
        if (keystore != null) {
            Files.deleteIfExists(keystore);
            Files.deleteIfExists(keystore.getParent());
        }
    }

    private static String call(HttpClientOptions options) throws Exception {
        var client = vertx.createHttpClient(options);
        try {
            return client.request(HttpMethod.GET, port, "localhost", "/v1/products/PRUDENT")
                    .compose(request -> request.send().compose(response -> response.body()
                            .map(Buffer::toString)))
                    .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void aCallWithoutAValidClientCertificateIsRejected() {
        // Le client fait confiance au serveur mais ne presente aucune identite : c'est exactement
        // la situation d'une charge de travail sans certificat de maillage.
        HttpClientOptions anonymous = new HttpClientOptions()
                .setSsl(true)
                .setPfxTrustOptions(workload());

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> call(anonymous),
                "un appel sans certificat client doit etre refuse, pas servi");
        assertTrue(refused.getCause() != null,
                "le refus doit remonter une cause de transport : " + refused);
    }

    @Test
    void aCallWithTheWorkloadCertificateSucceeds() throws Exception {
        HttpClientOptions withWorkloadIdentity = new HttpClientOptions()
                .setSsl(true)
                .setPfxKeyCertOptions(workload())
                .setPfxTrustOptions(workload());

        assertEquals("{\"code\":\"PRUDENT\"}", call(withWorkloadIdentity),
                "avec le certificat de charge de travail, l'appel doit aboutir");
    }

    @Test
    void theMeshConfigurationCarriesOnlyEnvironmentReferences() throws Exception {
        String properties = Files.readString(PROPERTIES);

        assertTrue(properties.contains(
                        "%prod.quarkus.rest-client.catalogue.tls-configuration-name=mesh"),
                "le client du catalogue doit utiliser la configuration TLS mesh");
        assertTrue(properties.contains(
                        "%prod.quarkus.rest-client.simulation.tls-configuration-name=mesh"),
                "le client de la simulation doit utiliser la configuration TLS mesh");
        assertTrue(properties.contains(
                        "%prod.quarkus.tls.mesh.key-store.pem.0.cert=${WORKLOAD_CERT}")
                        && properties.contains(
                        "%prod.quarkus.tls.mesh.key-store.pem.0.key=${WORKLOAD_KEY}")
                        && properties.contains(
                        "%prod.quarkus.tls.mesh.trust-store.pem.certs=${CLUSTER_CA}"),
                "certificat, cle et autorite doivent etre des references d'environnement");
        // Une valeur par defaut sur l'une de ces trois cles reintroduirait du materiel dans le
        // depot ou dans l'image : le motif ${VAR: cherche la syntaxe de defaut de Quarkus.
        assertTrue(!properties.contains("${WORKLOAD_CERT:")
                        && !properties.contains("${WORKLOAD_KEY:")
                        && !properties.contains("${CLUSTER_CA:"),
                "aucune de ces trois references ne doit avoir de valeur par defaut (NFG-004)");
    }
}
