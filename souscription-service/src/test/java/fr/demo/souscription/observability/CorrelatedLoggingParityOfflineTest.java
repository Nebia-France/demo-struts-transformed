package fr.demo.souscription.observability;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
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
import org.hamcrest.Matchers;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Variante hors conteneur de {@code CorrelatedLoggingParityTest} (plan 24 - verification de
 * parite, NFG-014).
 *
 * <p>Le squelette de parite exerce {@code /v1/drafts}, donc la base PostgreSQL des Dev Services :
 * il est neutralise quand aucun moteur de conteneurs n'est disponible. Les assertions qui ne
 * dependent pas du stockage sont rejouees ici sur le profil de test par defaut (source de donnees
 * inactive) : identifiant genere en l'absence d'en-tete, en-tete entrant honore et repete, MDC
 * vide entre deux requetes, ligne JSON portant {@code traceId} et {@code correlationId},
 * {@code correlationId} dans le corps problem+json, absence de donnee personnelle.
 *
 * <p>Le SDK OpenTelemetry est arme sans exporteur et l'echantillonneur force a {@code always_on} :
 * sous le ratio de production (0,1), une assertion de correlation reussirait ou echouerait au
 * hasard.
 */
@QuarkusTest
@TestProfile(CorrelatedLoggingParityOfflineTest.AlwaysSampleProfile.class)
class CorrelatedLoggingParityOfflineTest {

    /** Force l'echantillonnage de chaque requete ; le ratio de production reste inchange. */
    public static class AlwaysSampleProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.otel.sdk.disabled", "false",
                    "quarkus.otel.traces.exporter", "none",
                    "quarkus.otel.metrics.exporter", "none",
                    "quarkus.otel.logs.exporter", "none",
                    "quarkus.otel.traces.sampler", "always_on",
                    "quarkus.otel.traces.sampler.arg", "1",
                    "quarkus.log.console.json.fields.mdc.enabled", "true");
        }
    }

    /** Ressource de test : emet des lignes de journal dans le contexte d'une requete filtree. */
    @Path("/test/parity-logging")
    public static class ParityLoggingResource {

        private static final org.jboss.logging.Logger LOG =
                org.jboss.logging.Logger.getLogger(ParityLoggingResource.class);

        @GET
        @Operation(hidden = true)
        @Path("/emit")
        public String emit() {
            LOG.infof("Brouillon traite ; draftId=%s", UUID.randomUUID());
            return "ok";
        }
    }

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
    @EnabledUnlessPropertyFalse(named = "quarkus.otel.enabled",
            reason = "chemin de retour arriere de la vague 5 : sans l'extension OpenTelemetry le traceId du MDC est vide par construction ; la correlation par correlationId reste verifiee par les autres methodes")
    void everyLogLineIsJsonCarryingTraceIdAndCorrelationId() throws Exception {
        given().header(CorrelationFilter.CORRELATION_HEADER, "test-corr-2")
                .when().get("/test/parity-logging/emit")
                .then().statusCode(200);

        assertFalse(captured.isEmpty(), "aucune ligne de journal n'a ete capturee");
        ObjectMapper mapper = new ObjectMapper();
        io.quarkus.logging.json.runtime.JsonFormatter formatter =
                new io.quarkus.logging.json.runtime.JsonFormatter();
        for (ExtLogRecord record : captured) {
            String line = formatter.format(record);
            JsonNode json = mapper.readTree(line);
            assertTrue(json.isObject(), "la ligne n'est pas un objet JSON : " + line);
            assertEquals("test-corr-2", field(json, CorrelationFilter.CORRELATION_ID), line);
            String traceId = field(json, CorrelationFilter.TRACE_ID);
            assertTrue(traceId != null && !traceId.isBlank(),
                    "traceId absent ou vide (exige fields.mdc.enabled=true et un echantillonneur "
                    + "always_on) : " + line);
        }
    }

    @Test
    void inboundCorrelationIdIsHonouredAndEchoed() {
        String echoed = given().header(CorrelationFilter.CORRELATION_HEADER, "test-corr-1")
                .when().get("/test/parity-logging/emit")
                .then().statusCode(200)
                .extract().header(CorrelationFilter.CORRELATION_HEADER);

        assertEquals("test-corr-1", echoed,
                "la passerelle journalise la valeur emise : sans repetition, les deux cotes ne "
                + "peuvent plus etre rapproches");
        assertTrue(mdcValues(CorrelationFilter.CORRELATION_ID).contains("test-corr-1"),
                "l'en-tete entrant doit etre honore, non regenere");
    }

    @Test
    void missingCorrelationIdIsGenerated() {
        String echoed = given().when().get("/test/parity-logging/emit")
                .then().statusCode(200)
                .extract().header(CorrelationFilter.CORRELATION_HEADER);

        assertNotNull(echoed, "un identifiant doit etre genere en l'absence d'en-tete");
        assertTrue(echoed.matches("[0-9a-fA-F-]{36}"), "UUIDv4 attendu, obtenu : " + echoed);
    }

    @Test
    void problemBodyCarriesTheCorrelationId() {
        String correlationId = UUID.randomUUID().toString();
        given().header(CorrelationFilter.CORRELATION_HEADER, correlationId)
                .contentType("application/json")
                .body("{\"firstName\":\"\",\"lastName\":\"Dupont\","
                        + "\"email\":\"c.d@example.org\",\"birthDate\":\"1990-05-04\"}")
                .when().put("/v1/drafts/" + UUID.randomUUID() + "/subscriber")
                .then().statusCode(400)
                .body("correlationId", Matchers.is(correlationId));
    }

    @Test
    void noPersonalDataInLogs() {
        given().contentType("application/json")
                .body("{\"firstName\":\"Zoe\",\"lastName\":\"Berenger\","
                        + "\"email\":\"pas-une-adresse\",\"birthDate\":\"1990-05-12\"}")
                .when().put("/v1/drafts/" + UUID.randomUUID() + "/subscriber")
                .then().statusCode(400);

        io.quarkus.logging.json.runtime.JsonFormatter formatter =
                new io.quarkus.logging.json.runtime.JsonFormatter();
        for (ExtLogRecord record : captured) {
            String line = formatter.format(record);
            for (String personal : List.of("Berenger", "Zoe", "1990-05-12", "pas-une-adresse")) {
                assertFalse(line.contains(personal),
                        "CFG-011 : le source ne journalisait aucune donnee personnelle ; un "
                        + "entrepot de journaux a une retention plus longue et une audience plus "
                        + "large que la base. Trouve '" + personal + "' dans : " + line);
            }
        }
    }

    @Test
    void mdcDoesNotLeakBetweenRequests() {
        given().header(CorrelationFilter.CORRELATION_HEADER, "first-request")
                .when().get("/test/parity-logging/emit").then().statusCode(200);
        captured.clear();

        given().when().get("/test/parity-logging/emit").then().statusCode(200);

        assertFalse(mdcValues(CorrelationFilter.CORRELATION_ID).contains("first-request"),
                "les threads de travail sont mutualises : un MDC non purge attribue les lignes "
                + "d'un utilisateur a l'identifiant de correlation d'un autre");
    }

    private List<String> mdcValues(String key) {
        return captured.stream().map(record -> record.getMdc(key)).toList();
    }

    /** Le champ est lu a la racine ou dans l'objet {@code mdc}, selon le format JSON retenu. */
    private static String field(JsonNode json, String name) {
        JsonNode node = json.get(name);
        if (node == null && json.get("mdc") != null) {
            node = json.get("mdc").get(name);
        }
        return node == null || node.isNull() ? null : node.asText();
    }
}
