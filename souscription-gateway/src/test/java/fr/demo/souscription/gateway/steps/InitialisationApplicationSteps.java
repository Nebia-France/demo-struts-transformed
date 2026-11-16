package fr.demo.souscription.gateway.steps;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import io.cucumber.java.After;
import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Et;
import io.cucumber.java.fr.Quand;
import io.cucumber.java.fr.Soit;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Gateway glue for two scenarios of {@code features/initialisation-application/} (W6-T01),
 * reinterpreted for a stateless gateway:
 *
 * <ul>
 *   <li>« Demarrage nominal de l'application » &rarr; <strong>readiness at startup</strong>: a
 *       freshly started packaged gateway reports ready on its management interface, its downstream
 *       edges are wired, and its public surface is published — without any datastore of its own;
 *   <li>« Arret de l'application » &rarr; <strong>graceful drain at shutdown</strong>: on SIGTERM,
 *       the requests in flight complete (0 cut request) and the process exits within the
 *       {@code quarkus.shutdown.timeout} of 10 s.
 * </ul>
 *
 * <p>The H2 schema, the data directory and the JDBC drivers named by the feature belong to
 * souscription-service; the six H2/POSIX scenarios of that feature are not run here.
 *
 * <p>Each scenario starts its own packaged instance on {@code gateway.cucumber.port + 10}: the
 * shutdown scenario kills it, so the instance shared by the other glue classes is never touched.
 */
public class InitialisationApplicationSteps extends GatewayStepsSupport {

    /** Upper bound on startup-to-readiness for the gate; the 0.6–1.5 s target is measured apart. */
    private static final long READINESS_DEADLINE_MS = 30_000;

    /** {@code quarkus.shutdown.timeout}. */
    private static final long DRAIN_BUDGET_MS = 10_000;

    /** Downstream latency held by each in-flight request; below the 3 s catalogue read timeout. */
    private static final int IN_FLIGHT_DELAY_MS = 2_000;

    private static final int IN_FLIGHT_REQUESTS = 5;

    private static final String CATALOGUE = "catalogue";

    private Process instance;
    private File journal;
    private String dataDirectory;
    private long startupToReadinessMs;
    private long drainMs;
    private final List<Integer> inFlightStatuses = new ArrayList<>();

    @After("@initialisation-application")
    public void stopTheDedicatedInstance() {
        if (DownstreamWireMocks.isRunning(CATALOGUE)) {
            catalogue().setGlobalFixedDelay(0);
        }
        if (instance != null && instance.isAlive()) {
            instance.destroyForcibly();
        }
    }

    // ------------------------------------------------------------------ startup

    @Soit("le repertoire de donnees {string} est accessible en ecriture")
    public void theDataDirectoryIsWritable(String directory) {
        // The gateway holds no datastore: the directory is recorded only to prove, once started,
        // that the gateway did not create it (the schema belongs to souscription-service).
        this.dataDirectory = directory;
    }

    @Quand("le conteneur initialise le contexte de l'application")
    public void theContainerInitialisesTheContext() {
        startDedicatedInstance();
    }

    @Alors("le schema de la base des souscriptions est cree")
    public void theSubscriptionsSchemaIsCreated() {
        JsonPath ready = JsonPath.from(management().get("/q/health/ready").then()
                .statusCode(200).extract().asString());
        assertEquals("UP", ready.getString("status"));
        assertEquals(List.of("gateway-routes"), ready.getList("checks.name"),
                "la readiness ne doit refleter que la capacite propre de la passerelle");
        assertTrue(startupToReadinessMs < READINESS_DEADLINE_MS,
                "demarrage jusqu'a readiness : " + startupToReadinessMs + " ms");
        assertFalse(Files.exists(Path.of(dataDirectory)),
                "la passerelle est sans etat : elle ne cree aucun repertoire de donnees");
    }

    @Et("les services catalogue, validateur, simulation et souscription sont disponibles")
    public void theBusinessServicesAreAvailable() {
        JsonPath health = JsonPath.from(management().get("/q/health").then()
                .statusCode(200).extract().asString());
        assertEquals("UP", health.getString("status"));
        for (String edge : List.of("catalogue", "simulation", "subscription")) {
            assertEquals("CLOSED",
                    health.getString("checks.find { it.name == 'downstream' }.data." + edge),
                    "arete " + edge);
        }
        // The validator is part of souscription-service (subscription edge); the catalogue edge is
        // exercised end to end through the public surface.
        assertEquals(200, publicSurface().get("/api/v1/products").getStatusCode());
    }

    @Et("l'application est publiee dans le contexte de servlet")
    public void theApplicationIsPublished() {
        assertEquals(200, publicSurface().get("/api/v1/csrf-token").getStatusCode(),
                "la surface publique /api/v1 doit etre servie");
        for (String probe : List.of("/q/health/live", "/q/health/ready")) {
            assertNotEquals(200, publicSurface().get(probe).getStatusCode(),
                    probe + " ne doit pas etre servi sur le port public");
            assertEquals(200, management().get(probe).getStatusCode(),
                    probe + " doit etre servi sur l'interface de management");
        }
    }

    // ------------------------------------------------------------------ shutdown

    @Soit("l'application est initialisee")
    public void theApplicationIsInitialised() {
        startDedicatedInstance();
        assertEquals(200, management().get("/q/health/ready").getStatusCode());
    }

    @Quand("le conteneur detruit le contexte de l'application")
    public void theContainerDestroysTheContext() throws Exception {
        catalogue().setGlobalFixedDelay(IN_FLIGHT_DELAY_MS);
        ExecutorService clients = Executors.newFixedThreadPool(IN_FLIGHT_REQUESTS);
        try {
            List<Future<Integer>> inFlight = new ArrayList<>();
            for (int i = 0; i < IN_FLIGHT_REQUESTS; i++) {
                inFlight.add(clients.submit(() -> publicSurface().get("/api/v1/products").getStatusCode()));
            }
            awaitInFlight(IN_FLIGHT_REQUESTS);

            long sigterm = System.nanoTime();
            instance.destroy(); // SIGTERM, as sent by the kubelet after the preStop hook
            boolean exited = instance.waitFor(DRAIN_BUDGET_MS + 5_000, TimeUnit.MILLISECONDS);
            drainMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sigterm);
            assertTrue(exited, "le processus ne s'est pas arrete apres " + drainMs + " ms");

            for (Future<Integer> request : inFlight) {
                try {
                    inFlightStatuses.add(request.get(5, TimeUnit.SECONDS));
                } catch (Exception e) {
                    inFlightStatuses.add(-1); // connection cut
                }
            }
        } finally {
            clients.shutdownNow();
            catalogue().setGlobalFixedDelay(0);
        }
    }

    @Alors("les pilotes JDBC charges par l'application sont liberes")
    public void theResourcesAreReleased() throws IOException {
        assertEquals(IN_FLIGHT_REQUESTS, inFlightStatuses.size());
        assertTrue(inFlightStatuses.stream().allMatch(status -> status == 200),
                "requetes en vol coupees par l'arret : " + inFlightStatuses);
        assertTrue(drainMs <= DRAIN_BUDGET_MS, "drain de " + drainMs + " ms > 10 s");
        String log = Files.readString(journal.toPath(), StandardCharsets.UTF_8);
        assertTrue(log.contains("All HTTP requests complete"), "drain attendu dans " + journal);
        assertTrue(log.contains("stopped in"), "arret ordonne attendu dans " + journal);
    }

    // ------------------------------------------------------------------ support

    private int dedicatedPort() {
        return testPort() + 10;
    }

    private void startDedicatedInstance() {
        GatewayUnderTest.requirePackagedApplication();
        journal = new File("target/gateway-cucumber-lifecycle.log");
        long start = System.nanoTime();
        // The asserted shutdown record ("stopped in") is the last one Quarkus writes before the JVM
        // halts: the async console handler (W6-T03) may still hold it in its queue at that point,
        // so this dedicated instance logs synchronously. The drain itself is unchanged.
        instance = GatewayUnderTest.launch(dedicatedPort(), journal, "-Dquarkus.log.console.async=false");
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(READINESS_DEADLINE_MS);
        while (System.nanoTime() < deadline) {
            if (!instance.isAlive()) {
                throw new IllegalStateException("La passerelle s'est arretee au demarrage - voir " + journal);
            }
            if (readinessStatus() == 200) {
                startupToReadinessMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                return;
            }
            pause(50);
        }
        throw new IllegalStateException("Readiness non atteinte - voir " + journal);
    }

    private int readinessStatus() {
        try {
            return management().get("/q/health/ready").getStatusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Waits until {@code count} catalogue calls hold a connection (gauge published by PendingRequests). */
    private void awaitInFlight(int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String metrics = management().get("/q/metrics").asString();
            for (String line : metrics.split("\n")) {
                if (line.startsWith("http_client_pending_requests") && line.contains("clientName=\"catalogue\"")
                        && Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)) >= count) {
                    return;
                }
            }
            pause(20);
        }
        throw new IllegalStateException("Les requetes ne sont pas en vol vers le catalogue");
    }

    private RequestSpecification management() {
        return given().port(dedicatedPort() + 1);
    }

    private RequestSpecification publicSurface() {
        return given().port(dedicatedPort());
    }

    private static WireMockServer catalogue() {
        return DownstreamWireMocks.server(CATALOGUE);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
