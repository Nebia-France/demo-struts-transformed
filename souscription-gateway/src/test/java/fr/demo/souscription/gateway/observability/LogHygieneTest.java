package fr.demo.souscription.gateway.observability;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import fr.demo.souscription.gateway.edge.CsrfTestPair;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Filter;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logmanager.ExtHandler;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Non-regression test of the gateway journal (W6-T03).
 *
 * <p>The log output is captured while the negative security requests of
 * {@code features/securite-web} run (absent, empty, wrong, case-changed and oversized CSRF token,
 * forbidden methods, oversized body), plus a subscriber capture carrying personal data. Every
 * record is rendered by the formatter <em>and</em> the filter of the configured console handler, so
 * the captured lines are exactly the lines written to stdout. The test asserts:
 * <ul>
 *   <li>every line is valid JSON carrying {@code traceId}, {@code spanId} and {@code requestId};
 *   <li>exactly one {@code rejection} record per rejected request, its {@code requestId} equal to
 *       the {@code instance} of the problem document, and as many rejection records as the
 *       {@code gateway.rejections} counter moved;
 *   <li>0 occurrence of the CSRF token value, of the subscriber field names and of their values.
 * </ul>
 */
@QuarkusTest
class LogHygieneTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<String> CORRELATION_FIELDS = List.of("traceId", "spanId", "requestId");

    private static final List<String> PERSONAL_FIELD_NAMES =
            List.of("firstName", "lastName", "email", "birthDate");

    private static final Map<String, Object> SUBSCRIBER = Map.of(
            "firstName", "Alice", "lastName", "Durand",
            "email", "alice@example.com", "birthDate", "1990-01-15");

    private static final List<String> PERSONAL_VALUES =
            List.of("Alice", "Durand", "alice@example.com", "1990-01-15");

    @Inject
    MeterRegistry registry;

    private Capture capture;

    @BeforeEach
    void captureConsole() {
        DownstreamWireMocks.CONFIG_KEYS.forEach(DownstreamWireMocks::bringBack);
        capture = Capture.ofConsole();
        // The async handler enqueues the very record it receives and its worker thread then filters
        // and formats it: the capture must run first, before the record is shared with that thread.
        Handler[] current = rootLogger().getHandlers();
        Handler[] withCapture = new Handler[current.length + 1];
        withCapture[0] = capture;
        System.arraycopy(current, 0, withCapture, 1, current.length);
        rootLogger().setHandlers(withCapture);
    }

    @AfterEach
    void release() {
        rootLogger().removeHandler(capture);
    }

    @Test
    void consoleHandlerIsAsynchronousAndJson() {
        assertTrue(capture.asyncInChain, "le gestionnaire console doit etre asynchrone");
        assertTrue(capture.formatter.getClass().getName().contains("Json"),
                "formatteur console attendu JSON : " + capture.formatter.getClass().getName());
    }

    @Test
    void negativeSecurityRequestsLogOneCorrelatedRecordEachAndNoSecret() throws IOException {
        String token = issueToken();
        String other = issueToken();
        List<String> tokens = List.of(token, other);
        double countersBefore = rejectionCounters();

        List<Supplier<Response>> rejected = new ArrayList<>();
        rejected.add(() -> request(null, null).body(Map.of("productCode", "PRUDENT"))
                .post("/api/v1/drafts"));
        rejected.add(() -> request(token, "").body(Map.of("productCode", "PRUDENT"))
                .post("/api/v1/drafts"));
        rejected.add(() -> request(token, other).body(Map.of("productCode", "PRUDENT"))
                .post("/api/v1/drafts"));
        rejected.add(() -> request(token, flipCase(token)).body(Map.of("productCode", "PRUDENT"))
                .post("/api/v1/drafts"));
        rejected.add(() -> request(token, token + "x".repeat(101)).body(Map.of("productCode", "PRUDENT"))
                .post("/api/v1/drafts"));
        rejected.add(() -> request(null, token).body(SUBSCRIBER)
                .put("/api/v1/drafts/" + UUID.randomUUID() + "/subscriber"));
        rejected.add(() -> request(token, null).body(SUBSCRIBER)
                .put("/api/v1/drafts/" + UUID.randomUUID() + "/subscriber"));
        rejected.add(() -> request(token, token).delete("/api/v1/products"));
        rejected.add(() -> request(token, token).patch("/api/v1/drafts/" + UUID.randomUUID()));
        rejected.add(() -> request(null, null).options("/api/v1/products"));
        rejected.add(() -> request(null, null).request("TRACE", "/api/v1/products"));
        rejected.add(() -> request(token, token)
                .body("{\"productCode\":\"" + "A".repeat(65 * 1024) + "\"}")
                .post("/api/v1/drafts"));

        List<String> requestIds = new ArrayList<>();
        for (Supplier<Response> call : rejected) {
            Response response = call.get();
            int status = response.statusCode();
            assertTrue(status == 403 || status == 405 || status == 413, "rejet attendu, recu " + status);
            requestIds.add(response.jsonPath().getString("instance"));
        }
        double countersAfter = rejectionCounters();

        List<JsonNode> records = parseAll(capture.lines());
        List<JsonNode> rejections = records.stream().filter(LogHygieneTest::isRejection).toList();

        assertEquals(rejected.size(), rejections.size(), "une entree de rejet par requete rejetee");
        assertEquals(countersAfter - countersBefore, rejections.size(), 0.0001d,
                "nombre d'entrees de rejet = variation du compteur gateway.rejections");
        for (String id : requestIds) {
            assertNotNull(id);
            long forRequest = rejections.stream()
                    .filter(r -> id.equals(r.path("mdc").path("requestId").asText())).count();
            assertEquals(1, forRequest, "exactement une entree de rejet pour la requete " + id);
        }
        rejections.forEach(r -> assertFalse(r.path("mdc").path("requestId").asText().isEmpty(),
                "requestId renseigne sur une entree de rejet : " + r));

        assertNoSecretNorPersonalData(capture.lines(), tokens);
    }

    @Test
    void rejectionRecordRequestIdIsTheProblemInstance() throws IOException {
        String id = "req-" + UUID.randomUUID();
        String instance = given().header("X-Request-Id", id).contentType(ContentType.JSON)
                .body(Map.of("productCode", "PRUDENT"))
                .when().post("/api/v1/drafts")
                .then().statusCode(403).extract().jsonPath().getString("instance");
        assertEquals(id, instance);

        List<JsonNode> rejections = parseAll(capture.lines()).stream()
                .filter(LogHygieneTest::isRejection).toList();
        assertEquals(1, rejections.size());
        JsonNode record = rejections.get(0);
        assertEquals(id, record.path("mdc").path("requestId").asText());
        assertEquals("WARN", record.path("level").asText());
        assertEquals("csrf rejection reason=csrf-missing status=403 route=/api/v1/drafts",
                record.path("message").asText());
        assertEquals("souscription-gateway", record.path("service").asText());
    }

    @Test
    void rejectionRecordCarriesTheRouteTemplateNotTheRawPath() throws IOException {
        UUID draftId = UUID.randomUUID();
        given().contentType(ContentType.JSON).body(SUBSCRIBER)
                .when().put("/api/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(403);
        given().when().patch("/" + UUID.randomUUID() + "/PRUDENT").then().statusCode(405);

        List<String> lines = capture.lines();
        List<JsonNode> rejections = parseAll(lines).stream().filter(LogHygieneTest::isRejection).toList();
        assertEquals(2, rejections.size());
        assertTrue(rejections.get(0).path("message").asText()
                .endsWith("route=/api/v1/drafts/{id}/subscriber"));
        assertTrue(rejections.get(1).path("message").asText().endsWith("route=/unmatched"));
        lines.forEach(line -> {
            assertFalse(line.contains(draftId.toString()), "identifiant brut journalise : " + line);
            assertFalse(line.contains("PRUDENT"), "chemin brut journalise : " + line);
        });
    }

    @Test
    void acceptedSubscriberCaptureLogsNoPersonalData() throws IOException {
        String token = issueToken();
        String draftId = request(token, token).body(Map.of("productCode", "PRUDENT"))
                .post("/api/v1/drafts").then().statusCode(201).extract().path("draftId");
        request(token, token).body(SUBSCRIBER)
                .put("/api/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(200);
        // Invalid subscriber: the validation failure names the rejected fields in its message.
        request(token, token).body(Map.of("firstName", "", "lastName", "Durand",
                        "email", "alice@example.com", "birthDate", "1990-01-15"))
                .put("/api/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(400);

        List<String> lines = capture.lines();
        parseAll(lines);
        assertNoSecretNorPersonalData(lines, List.of(token));
    }

    private static boolean isRejection(JsonNode record) {
        return RejectionMetrics.class.getName().equals(record.path("loggerName").asText())
                && record.path("message").asText().contains(" rejection reason=");
    }

    /** Every line must be one JSON object carrying the three correlation fields. */
    private static List<JsonNode> parseAll(List<String> lines) throws IOException {
        assertFalse(lines.isEmpty(), "aucune ligne de journal capturee");
        List<JsonNode> records = new ArrayList<>();
        for (String line : lines) {
            JsonNode record;
            try {
                record = JSON.readTree(line);
            } catch (IOException e) {
                fail("ligne de journal non JSON : " + line);
                return records;
            }
            assertTrue(record.isObject(), "ligne de journal non objet JSON : " + line);
            JsonNode mdc = record.path("mdc");
            for (String field : CORRELATION_FIELDS) {
                assertTrue(mdc.has(field), "champ " + field + " absent : " + line);
            }
            records.add(record);
        }
        return records;
    }

    private static void assertNoSecretNorPersonalData(List<String> lines, List<String> tokens) {
        for (String line : lines) {
            for (String token : tokens) {
                assertFalse(line.contains(token), "valeur de jeton CSRF journalisee : " + line);
            }
            for (String field : PERSONAL_FIELD_NAMES) {
                assertFalse(line.contains(field), "champ personnel journalise : " + line);
            }
            for (String value : PERSONAL_VALUES) {
                assertFalse(line.contains(value), "donnee personnelle journalisee : " + line);
            }
        }
    }

    private double rejectionCounters() {
        return registry.find(RejectionMetrics.METRIC).counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private static String issueToken() {
        return given().when().get("/api/v1/csrf-token")
                .then().statusCode(200).extract().jsonPath().getString("token");
    }

    private static RequestSpecification request(String cookie, String header) {
        RequestSpecification spec = given().contentType(ContentType.JSON);
        if (cookie != null) {
            spec = spec.cookie(config("gateway.csrf.cookie-name"), cookie);
        }
        if (header != null) {
            spec = spec.header(config("gateway.csrf.header-name"), header);
        }
        return spec;
    }

    private static String flipCase(String token) {
        StringBuilder flipped = new StringBuilder(token.length());
        for (char c : token.toCharArray()) {
            flipped.append(Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
        }
        return flipped.toString();
    }

    /** The Quarkus root logger, owned by the JBoss LogManager context of the application. */
    private static org.jboss.logmanager.Logger rootLogger() {
        return org.jboss.logmanager.LogContext.getLogContext().getLogger("");
    }

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }

    /**
     * Renders each record with the formatter and the filter of the configured console handler, on
     * the logging thread, so a line is available as soon as the response that produced it is.
     */
    static final class Capture extends ExtHandler {

        final Formatter formatter;

        final List<Filter> filters;

        final boolean asyncInChain;

        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        private Capture(Formatter formatter, List<Filter> filters, boolean asyncInChain) {
            this.formatter = formatter;
            this.filters = filters;
            this.asyncInChain = asyncInChain;
        }

        static Capture ofConsole() {
            List<Handler> path = new ArrayList<>();
            if (!findConsole(rootLogger().getHandlers(), path)) {
                fail("gestionnaire console introuvable");
            }
            Handler console = path.get(path.size() - 1);
            List<Filter> filters = path.stream().map(Handler::getFilter)
                    .filter(java.util.Objects::nonNull).toList();
            boolean async = path.stream()
                    .anyMatch(h -> h instanceof org.jboss.logmanager.handlers.AsyncHandler);
            return new Capture(console.getFormatter(), filters, async);
        }

        private static boolean findConsole(Handler[] handlers, List<Handler> path) {
            for (Handler handler : handlers) {
                path.add(handler);
                if (handler instanceof org.jboss.logmanager.handlers.ConsoleHandler) {
                    return true;
                }
                if (handler instanceof ExtHandler ext && findConsole(ext.getHandlers(), path)) {
                    return true;
                }
                path.remove(path.size() - 1);
            }
            return false;
        }

        @Override
        protected void doPublish(ExtLogRecord record) {
            for (Filter filter : filters) {
                if (!filter.isLoggable(record)) {
                    return;
                }
            }
            for (String line : formatter.format(record).split("\\R")) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }

        List<String> lines() {
            synchronized (lines) {
                return List.copyOf(lines);
            }
        }
    }
}
