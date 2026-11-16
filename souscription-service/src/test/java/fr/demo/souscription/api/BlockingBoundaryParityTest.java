package fr.demo.souscription.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.application.SimulationUnavailableException;
import fr.demo.souscription.integration.CatalogueStubServer;
import fr.demo.souscription.integration.RemoteSimulationProfile;
import fr.demo.souscription.integration.SimulationComputeRequest;
import fr.demo.souscription.integration.SimulationLookup;
import fr.demo.souscription.integration.SimulationStubServer;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Verification de parite du plan 30 : la frontiere bloquante est explicite, et les sondes
 * distinguent une faute transitoire d'une indisponibilite durable.
 *
 * <p>Volet sans stockage. Ce qui est verifie ici ne depend d'aucune base : l'annotation effective
 * des six operations, la stabilite de la readiness sur un echec isole, son passage a {@code DOWN}
 * sur un disjoncteur durablement ouvert, l'independance de la liveness vis-a-vis des dependances,
 * et la propagation du {@code traceparent} jusqu'au fournisseur. Les deux assertions qui exigent un
 * stockage reel — absence d'avertissement de blocage en faisant jouer les six operations, et
 * « aucune souscription partielle » pour les quatre cas de chaos — vivent dans
 * {@link BlockingBoundaryDatabaseParityTest}, un profil de test s'appliquant par classe.
 *
 * <p>L'echantillonneur est force a {@code always_on} : sous le ratio de production (0,1)
 * l'assertion de trace serait verte au hasard et ne prouverait rien.
 *
 * <p>Ordre impose : le disjoncteur est un etat partage par l'application. Les cas « faute
 * transitoire » doivent s'executer avant de l'ouvrir durablement, sinon ils observeraient l'etat
 * laisse par le cas durable.
 */
@QuarkusTest
@TestProfile(BlockingBoundaryParityTest.TracedRemoteProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BlockingBoundaryParityTest {

    /** Clients distants pointes sur les bouchons, SDK de trace arme, chaque requete echantillonnee. */
    public static class TracedRemoteProfile extends RemoteSimulationProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("quarkus.otel.enabled", "true");
            overrides.put("quarkus.otel.sdk.disabled", "false");
            overrides.put("quarkus.otel.traces.exporter", "none");
            overrides.put("quarkus.otel.metrics.exporter", "none");
            overrides.put("quarkus.otel.logs.exporter", "none");
            overrides.put("quarkus.otel.traces.sampler", "always_on");
            overrides.put("quarkus.otel.traces.sampler.arg", "1");
            return overrides;
        }
    }

    /**
     * Ressource de test : declenche l'appel sortant de simulation depuis une requete HTTP entrante,
     * seul moyen sans base d'obtenir un contexte de trace entrant puis un saut reseau sortant.
     */
    @Path("/test/parity-blocking")
    public static class ParityBlockingResource {

        @Inject
        SimulationLookup simulations;

        @GET
        @Blocking
        @org.eclipse.microprofile.openapi.annotations.Operation(hidden = true)
        @Path("/compute")
        public String compute() {
            return simulations.compute(STANDARD).finalCapital();
        }
    }

    static final SimulationComputeRequest STANDARD =
            new SimulationComputeRequest("1000.00", "100.00", 10, "0.020000");

    private static SimulationStubServer simulation;

    @Inject
    SimulationLookup simulations;

    @Inject
    MeterRegistry registry;

    @Inject
    @org.eclipse.microprofile.health.Readiness
    fr.demo.souscription.health.SimulationReadinessCheck simulationReadiness;

    @BeforeAll
    static void startStub() throws IOException {
        simulation = new SimulationStubServer();
    }

    @AfterAll
    static void stopStub() {
        simulation.close();
    }

    /** Etape 1 : les six operations portent {@code @Blocking}, et aucune autre ne manque. */
    @Test
    @Order(1)
    void sixOperationsCarryBlocking() {
        List<Method> operations = new ArrayList<>();
        for (Class<?> resource : List.of(SubscriptionResource.class,
                SubscriptionReadResource.class)) {
            for (Method method : resource.getDeclaredMethods()) {
                if (isHttpOperation(method)) {
                    operations.add(method);
                }
            }
        }

        assertEquals(6, operations.size(),
                "le contrat gele expose six operations ; toute operation supplementaire doit "
                + "etre couverte par la meme regle. Vu : " + names(operations));
        List<Method> unannotated = operations.stream()
                .filter(method -> method.getAnnotation(Blocking.class) == null
                        && method.getDeclaringClass().getAnnotation(Blocking.class) == null)
                .toList();
        assertTrue(unannotated.isEmpty(),
                "chaque operation touche la base par Panache ou emet un appel client bloquant : "
                + "sans @Blocking, l'acces JDBC peut etre dispatche sur la boucle d'evenements et "
                + "y bloquer un thread partage par toutes les connexions. Sans annotation : "
                + names(unannotated));
    }

    /**
     * Etape 11 : le {@code traceparent} entrant survit au saut reseau sortant. L'assertion porte
     * sur l'en-tete <b>recu par le bouchon</b> : une trace lue cote appelant resterait vraie meme
     * si rien n'etait emis sur le fil.
     */
    @Test
    @Order(2)
    void traceparentIsPropagatedOutbound() {
        simulation.reset();
        simulation.serveComputation();
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        given().header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .when().get("/test/parity-blocking/compute")
                .then().statusCode(200);

        String received = simulation.lastTraceparent();
        assertNotNull(received,
                "le fournisseur n'a recu aucun traceparent : la vue de bout en bout exigee par "
                + "NFG-016 s'arrete au premier saut et la latence par saut n'est plus attribuable");
        assertTrue(received.contains(traceId),
                "le traceparent sortant doit porter le traceId entrant, pas une nouvelle trace. "
                + "Vu : " + received);
    }

    /**
     * Etape 4 : un echec isole ne retire pas l'instance de la rotation. Une readiness qui bascule
     * au premier echec retire toutes les repliques en meme temps — le passant n'a plus aucune
     * destination, ce qui est strictement pire que de servir des 503 depuis une instance prete.
     */
    @Test
    @Order(3)
    void transientFailureDoesNotTakeTheInstanceOutOfRotation() {
        simulation.reset();
        simulation.serveStatus(500);

        assertThrows(SimulationUnavailableException.class, () -> simulations.compute(STANDARD));

        assertDependencyCheck("simulation", "UP");
        assertDependencyCheck("catalogue", "UP");
        assertLivenessUp();
    }

    /** Etape 6 : la liveness ne depend d'aucune dependance distante. */
    @Test
    @Order(4)
    void livenessStaysUpWhenDependenciesAreDown() throws IOException {
        simulation.reset();
        simulation.close();
        try {
            assertThrows(SimulationUnavailableException.class,
                    () -> simulations.compute(STANDARD));
            // Le catalogue n'a aucun bouchon demarre dans cette classe : les deux dependances sont
            // donc injoignables au moment de l'interrogation.
            assertLivenessUp();
        } finally {
            simulation = new SimulationStubServer();
            simulation.serveComputation();
        }
    }

    /**
     * Etape 5 : une indisponibilite durable rend l'instance inapte, la liveness restant {@code UP}.
     *
     * <p>Le disjoncteur est reellement ouvert par des echecs reels ; seule l'<b>anciennete</b> de
     * l'ouverture est antidatee, pour ne pas faire durer le test les 30 s de la fenetre
     * documentee. L'etat lui-meme est lu par la sonde dans les jauges Micrometer de SmallRye Fault
     * Tolerance, jamais re-suivi.
     */
    @Test
    @Order(5)
    void durablyOpenBreakerReportsNotReady() throws Exception {
        simulation.serveStatus(500);
        openTheSimulationBreaker();

        backdateTheOpenWindow();

        given().when().get("/q/health/ready")
                .then().statusCode(503)
                .body("checks.find { it.name == 'simulation' }.status", is("DOWN"));
        assertLivenessUp();
    }

    /** Provoque l'ouverture du disjoncteur (volume 20, ratio 0,5) et la constate. */
    private void openTheSimulationBreaker() {
        for (int attempt = 0; attempt < 80; attempt++) {
            try {
                simulations.compute(STANDARD);
            } catch (SimulationUnavailableException outage) {
                if (outage.getCause() instanceof CircuitBreakerOpenException) {
                    return;
                }
            }
        }
        throw new AssertionError("le disjoncteur de la simulation ne s'est pas ouvert apres 80 "
                + "echecs : sans disjoncteur ouvert, la readiness n'a aucun signal durable a lire");
    }

    /**
     * Antidate la fenetre d'ouverture continue de la sonde de 31 s, soit au-dela des 30 s
     * documentees. La jauge d'etat est lue deux fois a quelques centaines de millisecondes
     * d'intervalle : le cumul doit progresser, sinon la sonde ne verrait aucune ouverture et
     * l'antidatation ne prouverait rien.
     */
    private void backdateTheOpenWindow() throws Exception {
        Object check = ClientProxy.unwrap(simulationReadiness);
        Field windowField = check.getClass().getDeclaredField("window");
        windowField.setAccessible(true);
        Object window = windowField.get(check);
        Method continuouslyOpenFor = window.getClass()
                .getDeclaredMethod("continuouslyOpenFor", MeterRegistry.class, long.class);
        continuouslyOpenFor.setAccessible(true);

        long now = System.currentTimeMillis();
        continuouslyOpenFor.invoke(window, registry, now - 60_000);
        Thread.sleep(500);
        continuouslyOpenFor.invoke(window, registry, now - 31_000);
    }

    private void assertDependencyCheck(String name, String status) {
        RestAssured.when().get("/q/health/ready")
                .then().body("checks.find { it.name == '" + name + "' }.status", is(status));
        // Le verdict global est volontairement ignore : sans base armee, la sonde database est
        // DOWN pour une raison etrangere a ce que ce cas verifie.
    }

    private static void assertLivenessUp() {
        RestAssured.when().get("/q/health/live")
                .then().statusCode(200).body("status", is("UP"));
    }

    static boolean isHttpOperation(Method method) {
        for (Class<? extends java.lang.annotation.Annotation> verb :
                List.of(GET.class, POST.class, PUT.class, DELETE.class)) {
            if (method.getAnnotation(verb) != null) {
                return true;
            }
        }
        return method.getAnnotation(HttpMethod.class) != null;
    }

    private static String names(List<Method> methods) {
        return methods.stream()
                .map(method -> method.getDeclaringClass().getSimpleName() + "." + method.getName())
                .sorted()
                .toList()
                .toString();
    }
}

/**
 * Volet de la verification de parite du plan 30 qui exige un stockage reel.
 *
 * <p>Deux assertions ne sont demontrables qu'avec une base : l'absence d'avertissement du detecteur
 * de blocage de Vert.x <b>pendant que les six operations sont reellement executees</b>, et
 * l'invariant « aucune souscription partielle » pour les quatre cas de chaos, qui se lit dans
 * {@code SELECT count(*) FROM subscriptions.subscriptions}. Ignoree sans daemon de conteneurs,
 * comme les autres tests de parcours complet du module.
 */
@QuarkusTest
@TestProfile(fr.demo.souscription.integration.ChaosProfile.class)
@RequiresContainerRuntime
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BlockingBoundaryDatabaseParityTest {

    private static final List<String> CAPTURED = new CopyOnWriteArrayList<>();

    private static Handler capture;

    @Inject
    fr.demo.souscription.persistence.SubscriptionRepository repository;

    @BeforeAll
    static void captureRootLog() {
        capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage() != null) {
                    CAPTURED.add(record.getLoggerName() + " " + record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        LogManager.getLogManager().getLogger("").addHandler(capture);
    }

    @AfterAll
    static void releaseRootLog() {
        LogManager.getLogManager().getLogger("").removeHandler(capture);
        CAPTURED.clear();
    }

    /**
     * Etape 2 : faire jouer les six operations ne produit aucun avertissement de blocage de la
     * boucle d'evenements. Un tel avertissement signifie qu'une requete peut en stopper toutes les
     * autres — degradation que le modele thread-par-requete de la source ne pouvait pas exhiber.
     */
    @Test
    @Order(1)
    void allSixOperationsRunWithoutEventLoopWarning() throws IOException {
        try (CatalogueStubServer catalogue = new CatalogueStubServer();
                SimulationStubServer simulation = new SimulationStubServer()) {
            CAPTURED.clear();

            String draftId = startDraft();
            given().when().get("/v1/drafts/" + draftId).then().statusCode(200);
            putSubscriber(draftId);
            String key = putSimulation(draftId);
            String reference = given().contentType(ContentType.JSON)
                    .body("{\"confirmationKey\":\"" + key + "\"}")
                    .when().post("/v1/drafts/" + draftId + "/confirmation")
                    .then().statusCode(201)
                    .extract().path("reference");
            given().when().get("/v1/subscriptions/" + reference).then().statusCode(200);

            List<String> blocked = CAPTURED.stream()
                    .filter(line -> line.contains("Thread blocked") || line.contains("blocked for"))
                    .toList();
            assertTrue(blocked.isEmpty(),
                    "le detecteur de Vert.x a signale un blocage de la boucle d'evenements : "
                    + blocked);
        }
    }

    /**
     * Etape 7 : aucun des quatre cas de chaos ne laisse de souscription partielle, et la liveness
     * reste {@code UP}. Seul le cas de succes (latence sous le timeout) ajoute une ligne, et une
     * seule. Zero ligne partielle est non negociable : c'est le descendant direct de la branche
     * {@code SQLException} de la source, qui n'emettait aucune confirmation et conservait la
     * saisie.
     */
    @Test
    @Order(2)
    void chaosCasesLeaveNoPartialSubscription() throws IOException {
        // Cas 1 : simulation arretee.
        try (CatalogueStubServer catalogue = new CatalogueStubServer();
                SimulationStubServer simulation = new SimulationStubServer()) {
            String[] draft = completeDraft();
            long before = subscriptionCount();
            simulation.close();
            confirm(draft, 503);
            assertLivenessUp();
            assertEquals(before, subscriptionCount(),
                    "cas 1 (simulation arretee) : aucune souscription partielle");
        }

        // Cas 2 : catalogue arrete apres la constitution du brouillon.
        try (SimulationStubServer simulation = new SimulationStubServer()) {
            String[] draft;
            try (CatalogueStubServer catalogue = new CatalogueStubServer()) {
                draft = completeDraft();
            }
            long before = subscriptionCount();
            confirm(draft, 503);
            assertLivenessUp();
            assertEquals(before, subscriptionCount(),
                    "cas 2 (catalogue arrete) : aucune souscription partielle, le taux persiste "
                    + "etant toujours celui du catalogue");
        }

        // Cas 3 : latence 2,5 s, sous le timeout de 3 s — doit reussir, et n'ajouter qu'une ligne.
        try (CatalogueStubServer catalogue = new CatalogueStubServer();
                SimulationStubServer simulation = new SimulationStubServer()) {
            String[] draft = completeDraft();
            long before = subscriptionCount();
            simulation.serveWithLatency(2500);
            confirm(draft, 201);
            assertLivenessUp();
            assertEquals(before + 1, subscriptionCount(),
                    "cas 3 (latence 2500 ms) : une dependance lente mais non defaillante doit "
                    + "aboutir a une souscription complete, et a une seule");
        }

        // Cas 4 : latence 3,5 s, au-dela du timeout — 503 rejouable, aucune ligne.
        try (CatalogueStubServer catalogue = new CatalogueStubServer();
                SimulationStubServer simulation = new SimulationStubServer()) {
            String[] draft = completeDraft();
            long before = subscriptionCount();
            simulation.serveWithLatency(3500);
            confirm(draft, 503);
            assertLivenessUp();
            assertEquals(before, subscriptionCount(),
                    "cas 4 (latence 3500 ms) : un depassement de delai ne doit creer aucune "
                    + "souscription partielle");
        }
    }

    private void confirm(String[] draft, int status) {
        var response = given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + draft[1] + "\"}")
                .when().post("/v1/drafts/" + draft[0] + "/confirmation")
                .then().statusCode(status);
        if (status == 503) {
            response.contentType("application/problem+json")
                    .body("type", Matchers.startsWith("urn:horizon:"));
        }
    }

    private long subscriptionCount() {
        return QuarkusTransaction.requiringNew().call(() -> repository.count());
    }

    private String[] completeDraft() {
        String draftId = startDraft();
        putSubscriber(draftId);
        return new String[] {draftId, putSimulation(draftId)};
    }

    private static String startDraft() {
        return given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts")
                .then().statusCode(201)
                .extract().path("draftId");
    }

    private static void putSubscriber(String draftId) {
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(200);
    }

    private static String putSimulation(String draftId) {
        return given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put("/v1/drafts/" + draftId + "/simulation")
                .then().statusCode(200)
                .extract().path("confirmationKey");
    }

    private static void assertLivenessUp() {
        RestAssured.when().get("/q/health/live")
                .then().statusCode(200).body("status", is("UP"));
    }
}
