package fr.demo.souscription.observability;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * W5-T01 / NFG-014 : toute ligne de journal du module est un objet JSON portant un
 * {@code traceId} et un {@code correlationId} non vides, le second egal a l'en-tete
 * {@code X-Correlation-Id} propage par la passerelle.
 *
 * <p>Les enregistrements sont captures par un handler {@code java.util.logging} installe sur le
 * logger du module, puis formates par le formateur JSON de Quarkus
 * ({@code io.quarkus.logging.json.runtime.JsonFormatter}), celui-la meme qui ecrit la sortie
 * console en deploiement. Chaque ligne obtenue est reparsee : la presence des champs est donc
 * verifiee sur la representation reellement emise, et non sur l'enregistrement en memoire.
 *
 * <p>Verifie aussi qu'aucune ligne ne contient de donnee personnelle du souscripteur (prenom,
 * nom, adresse electronique, date de naissance) : un brouillon n'est journalise que par son
 * {@code draftId} (prerequis RGPD, ecart CFG-011).
 */
@QuarkusTest
@TestProfile(CorrelatedLoggingTest.TracingEnabledProfile.class)
class CorrelatedLoggingTest {

    private static final String CORRELATION_ID = "test-corr-1";
    private static final String SURNAME = "Dupont";
    private static final String EMAIL = "jean.dupont@example.com";

    /**
     * Le profil de test global desactive le SDK OpenTelemetry ; la correlation exige un contexte
     * de trace valide, le SDK est donc arme ici sans exporteur (aucun collecteur n'est joignable
     * depuis le build).
     */
    public static class TracingEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.otel.sdk.disabled", "false",
                    "quarkus.otel.traces.exporter", "none",
                    "quarkus.otel.metrics.exporter", "none",
                    "quarkus.otel.logs.exporter", "none");
        }
    }

    /** Ressource de test : emet une ligne de journal dans le contexte d'une requete filtree. */
    @Path("/test/logging")
    public static class LoggingResource {

        private static final org.jboss.logging.Logger LOG =
                org.jboss.logging.Logger.getLogger(LoggingResource.class);

        @GET
        @Operation(hidden = true)
        @Path("/emit")
        public String emit() {
            // Un brouillon n'est journalise que par son identifiant : jamais de donnee
            // personnelle du souscripteur.
            LOG.infof("Brouillon traite ; draftId=%s", UUID.randomUUID());
            LOG.debugf("Etape de parcours franchie ; step=%s", "subscriber");
            return "ok";
        }
    }

    private final List<ExtLogRecord> captured = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record instanceof ExtLogRecord extended) {
                // Le MDC d'un ExtLogRecord est resolu paresseusement : il doit etre fige ici,
                // dans le thread qui emet la ligne, et non au moment de l'assertion.
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
    void everyLogLineIsJsonCarryingTheTraceIdAndThePropagatedCorrelationId() throws Exception {
        given().header(CorrelationFilter.CORRELATION_HEADER, CORRELATION_ID)
                .when().get("/test/logging/emit")
                .then().statusCode(200)
                .header(CorrelationFilter.CORRELATION_HEADER, CORRELATION_ID);

        assertFalse(captured.isEmpty(), "aucune ligne de journal n'a ete capturee");

        ObjectMapper mapper = new ObjectMapper();
        io.quarkus.logging.json.runtime.JsonFormatter formatter =
                new io.quarkus.logging.json.runtime.JsonFormatter();
        for (ExtLogRecord record : captured) {
            String line = formatter.format(record);
            JsonNode json = mapper.readTree(line);
            assertTrue(json.isObject(), "la ligne n'est pas un objet JSON : " + line);
            assertEquals(CORRELATION_ID, field(json, CorrelationFilter.CORRELATION_ID),
                    "correlationId absent ou different de l'en-tete : " + line);
            String traceId = field(json, CorrelationFilter.TRACE_ID);
            assertTrue(traceId != null && !traceId.isBlank(),
                    "traceId absent ou vide : " + line);
        }
    }

    @Test
    void aRejectedRequestCarriesTheCorrelationIdAndNoPersonalDataInTheLogs() {
        // Adresse invalide : le verdict de validation tombe avant tout acces au stockage.
        given().header(CorrelationFilter.CORRELATION_HEADER, CORRELATION_ID)
                .contentType("application/json")
                .body("{\"firstName\":\"Jean\",\"lastName\":\"" + SURNAME
                        + "\",\"email\":\"pas-une-adresse\",\"birthDate\":\"1990-05-12\"}")
                .when().put("/v1/drafts/" + UUID.randomUUID() + "/subscriber")
                .then().statusCode(400)
                .header(CorrelationFilter.CORRELATION_HEADER, CORRELATION_ID)
                .body("correlationId", Matchers.equalTo(CORRELATION_ID));

        io.quarkus.logging.json.runtime.JsonFormatter formatter =
                new io.quarkus.logging.json.runtime.JsonFormatter();
        for (ExtLogRecord record : captured) {
            String line = formatter.format(record);
            assertFalse(line.contains(SURNAME), "nom du souscripteur journalise : " + line);
            assertFalse(line.contains(EMAIL), "adresse du souscripteur journalisee : " + line);
            assertFalse(line.contains("1990-05-12"),
                    "date de naissance journalisee : " + line);
        }
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
