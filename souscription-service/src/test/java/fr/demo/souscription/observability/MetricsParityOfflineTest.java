package fr.demo.souscription.observability;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Volet hors conteneur de la verification de parite du plan 26 : tout ce qui est observable sans
 * base PostgreSQL. {@link MetricsParityTest} couvre le parcours complet et
 * {@code agroal_active_count}, qui exigent un moteur de conteneurs.
 *
 * <p>Verifie ici : exposition de {@code /q/metrics} et des familles par defaut sans base,
 * pre-declaration des cinq compteurs metier, unicite du nom du compteur de doublons, comptage
 * unique des rejets de validation, etiquette de statut rendant le SLI 99,9 % calculable, et
 * continuation d'un {@code traceparent} entrant jusqu'au MDC des journaux.
 *
 * <p>Le SDK OpenTelemetry est arme sans exporteur et l'echantillonneur force a {@code always_on} :
 * sous le ratio de production (0,1) l'assertion de trace reussirait ou echouerait au hasard, et une
 * execution verte ne prouverait rien. Le profil est propre a cette classe : partager celui d'une
 * autre classe de test ferait entrer ce bean de test dans son deploiement, qui echouerait a
 * demarrer sur le chemin de retour arriere {@code micrometer.enabled=false}.
 */
@EnabledUnlessPropertyFalse(named = "quarkus.micrometer.enabled",
        reason = "chemin de retour arriere de la vague 5 : sans registre de metriques il n'y a aucune serie a verifier ; le comportement metier est verifie par les autres tests, qui passent avec l'instrumentation desarmee")
@EnabledUnlessPropertyFalse(named = "quarkus.otel.enabled",
        reason = "l'assertion de continuation de trace exige l'extension OpenTelemetry armee")
@QuarkusTest
@TestProfile(MetricsParityOfflineTest.TracingAlwaysOnProfile.class)
class MetricsParityOfflineTest {

    /** SDK arme, aucun exporteur joignable depuis le build, chaque requete echantillonnee. */
    public static class TracingAlwaysOnProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("quarkus.otel.enabled", "true"),
                    Map.entry("quarkus.otel.sdk.disabled", "false"),
                    Map.entry("quarkus.otel.traces.exporter", "none"),
                    Map.entry("quarkus.otel.metrics.exporter", "none"),
                    Map.entry("quarkus.otel.logs.exporter", "none"),
                    Map.entry("quarkus.otel.traces.sampler", "always_on"),
                    Map.entry("quarkus.otel.traces.sampler.arg", "1"),
                    Map.entry("quarkus.log.console.json.fields.mdc.enabled", "true"));
        }
    }

    /** Ressource de test : emet une ligne de journal dans le contexte de trace de la requete. */
    @Path("/test/parity-tracing")
    public static class ParityTracingResource {

        private static final org.jboss.logging.Logger LOG =
                org.jboss.logging.Logger.getLogger(ParityTracingResource.class);

        @GET
        @Operation(hidden = true)
        @Path("/emit")
        public String emit() {
            LOG.infof("Brouillon traite ; draftId=%s", UUID.randomUUID());
            return "ok";
        }
    }

    @Inject
    MeterRegistry registry;

    private final List<ExtLogRecord> captured = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record instanceof ExtLogRecord extended) {
                // Le MDC est resolu paresseusement : il doit etre fige dans le thread emetteur.
                extended.copyMdc();
                captured.add(extended);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private Logger moduleLogger;

    @BeforeEach
    void installHandler() {
        captured.clear();
        moduleLogger = Logger.getLogger("fr.demo.souscription");
        moduleLogger.addHandler(handler);
    }

    @AfterEach
    void removeHandler() {
        moduleLogger.removeHandler(handler);
    }

    @Test
    void defaultMetricFamiliesAreExposedWithoutDatabase() {
        // Une requete applicative d'abord : l'histogramme du serveur HTTP n'a de serie qu'apres
        // la premiere requete observee.
        given().when().get("/test/parity-tracing/emit").then().statusCode(200);
        String body = scrape();
        for (String family : new String[] {"http_server_requests_seconds", "jvm_memory_"}) {
            assertTrue(body.contains(family),
                    "/q/metrics doit exposer la famille par defaut " + family);
        }
    }

    @Test
    void theFiveBusinessCountersArePreRegistered() {
        for (String name : new String[] {BusinessMetrics.DRAFTS_CREATED,
                BusinessMetrics.CONFIRMATIONS, BusinessMetrics.DUPLICATES,
                BusinessMetrics.VALIDATION_REJECTED, BusinessMetrics.DRAFT_PURGED}) {
            assertNotNull(registry.find(name).counter(),
                    name + " doit etre enregistre des le demarrage, meme a zero : une serie "
                    + "absente est indistinguable d'un scrape casse");
        }
        String body = scrape();
        for (String series : new String[] {"horizon_drafts_created_total",
                "horizon_confirmations_succeeded_total", "horizon_confirmations_duplicate_total",
                "horizon_validation_rejected_total", "horizon_draft_purged_total"}) {
            assertTrue(body.contains(series), "/q/metrics doit exposer " + series);
        }
    }

    @Test
    void validationRejectionsAreCountedOnce() {
        double before = count(BusinessMetrics.VALIDATION_REJECTED);

        // Deux violations dans une seule requete : un rejet = un increment, tranche par
        // ConstraintViolationMapper avant tout acces au stockage.
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"\",\"lastName\":\"\",\"email\":\"pas-une-adresse\","
                        + "\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + UUID.randomUUID() + "/subscriber")
                .then().statusCode(400);

        assertEquals(1.0d, count(BusinessMetrics.VALIDATION_REJECTED) - before, 0.0d,
                "l'increment appartient a ConstraintViolationMapper et a lui seul : un increment "
                + "par ressource double-compterait une requete portant deux violations");
    }

    @Test
    void availabilitySliIsComputable() {
        given().when().get("/test/parity-tracing/emit").then().statusCode(200);
        String body = scrape();

        String sample = body.lines()
                .filter(line -> line.startsWith("http_server_requests_seconds_count"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "http_server_requests_seconds_count absent : sans cette serie le SLI de "
                        + "disponibilite 99,9 % n'est calculable d'aucune facon"));
        assertTrue(sample.contains("status=") || sample.contains("outcome="),
                "la serie doit porter une etiquette status ou outcome pour partitionner succes et "
                + "erreurs. Vu : " + sample);
    }

    @Test
    void inboundTraceparentIsContinued() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        given().header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .when().get("/test/parity-tracing/emit")
                .then().statusCode(200);

        assertFalse(captured.isEmpty(), "aucune ligne de journal n'a ete capturee");
        List<String> seen = captured.stream()
                .map(record -> record.getMdc(CorrelationFilter.TRACE_ID))
                .toList();
        assertTrue(seen.contains(traceId),
                "le traceId du MDC doit etre celui de l'en-tete entrant : une trace regeneree "
                + "casse la vue a quatre processus exigee par NFG-016 et rend la latence par saut "
                + "non attribuable. Vu : " + seen);
    }

    private String scrape() {
        return given().when().get("/q/metrics").then().statusCode(200).extract().asString();
    }

    private double count(String name) {
        return registry.find(name).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }
}
