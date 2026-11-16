package fr.demo.souscription.gateway.observability;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import fr.demo.souscription.gateway.edge.CsrfTestPair;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Distributed tracing of the gateway (W6-T04).
 *
 * <p>The profile turns the OpenTelemetry SDK on with a uniform ratio of <strong>0</strong>: a span
 * is exported only because its parent was sampled, or because the error-always rule kept it. Spans
 * reach the {@link InMemorySpanExporter}; the downstream doubles record the {@code traceparent}
 * they receive. The test asserts:
 * <ul>
 *   <li>an incoming {@code traceparent} keeps its trace id on 3/3 downstream edges;</li>
 *   <li>the confirmation journey is a single trace: one server span, one journey span, one client
 *       span per downstream call;</li>
 *   <li>without {@code traceparent} the gateway creates the root;</li>
 *   <li>error responses are exported 100 % of the time, success at the uniform ratio only;</li>
 *   <li>no span attribute, event or name carries personal data, a token or a confirmation key,
 *       and every attribute key belongs to the documented allow-list;</li>
 *   <li>the {@code traceId}/{@code spanId} of the JSON log records are those of the active span.</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(TracingTest.TracingEnabled.class)
class TracingTest {

    public static class TracingEnabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.otel.sdk.disabled", "false",
                    "quarkus.otel.traces.sampler.arg", "0.0",
                    "quarkus.otel.bsp.schedule.delay", "50ms");
        }
    }

    private static final String TRACEPARENT = "traceparent";

    private static final String CONFIRMATION_KEY = "22222222-2222-2222-2222-222222222222";

    private static final String DRAFT_ID = "11111111-1111-1111-1111-111111111111";

    private static final String SIMULATION_BODY = "{\"productCode\":\"EQUILIBRE\","
            + "\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\",\"durationYears\":5}";

    private static final Map<String, Object> SUBSCRIBER = Map.of(
            "firstName", "Camille", "lastName", "Martin",
            "email", "camille@example.test", "birthDate", "1980-01-01");

    private static final List<String> PERSONAL_FIELD_NAMES =
            List.of("firstName", "lastName", "email", "birthDate");

    private static final List<String> PERSONAL_VALUES =
            List.of("Camille", "Martin", "camille@example.test", "1980-01-01");

    /**
     * Attribute keys a gateway span may carry (docs/observability.md section 9.4). Anything else is
     * a new attribute that must be reviewed for personal data before being allowed.
     */
    static final Set<String> ALLOWED_ATTRIBUTES = Set.of(
            "gateway.journey",
            "code.function", "code.namespace",
            "http.request.method", "http.response.status_code", "http.route",
            "http.request.body.size", "http.response.body.size",
            "url.scheme", "url.path", "url.full",
            "server.address", "server.port",
            "network.protocol.version", "network.peer.address", "network.peer.port",
            "client.address", "user_agent.original",
            "error.type", "exception.type", "exception.message", "exception.stacktrace",
            "exception.escaped");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    InMemorySpanExporter spans;

    @BeforeEach
    void reset() {
        DownstreamWireMocks.CONFIG_KEYS.forEach(DownstreamWireMocks::bringBack);
        DownstreamWireMocks.CONFIG_KEYS.forEach(key -> DownstreamWireMocks.server(key).resetRequests());
        spans.reset();
    }

    @AfterEach
    void restoreMappings() {
        DownstreamWireMocks.server("subscription").resetToDefaultMappings();
        DownstreamWireMocks.server("subscription").resetScenarios();
    }

    @Test
    void incomingTraceparentKeepsItsTraceIdOnTheThreeDownstreamEdges() {
        String traceId = randomTraceId();
        // Simulation: catalogue (rate) then simulation. Draft start: subscription and catalogue.
        CsrfTestPair.withCsrf().header(TRACEPARENT, traceparent(traceId, true))
                .contentType(ContentType.JSON).body(SIMULATION_BODY)
                .when().post("/api/v1/simulations").then().statusCode(200);
        CsrfTestPair.withCsrf().header(TRACEPARENT, traceparent(traceId, true))
                .contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/api/v1/drafts").then().statusCode(201);

        for (String edge : DownstreamWireMocks.CONFIG_KEYS) {
            List<String> received = outgoingTraceparents(edge);
            assertFalse(received.isEmpty(), "aucun appel vers " + edge);
            for (String header : received) {
                assertEquals(traceId, header.split("-")[1],
                        "identite de trace modifiee sur l'arete " + edge + " : " + header);
                assertTrue(header.endsWith("-01"), "decision d'echantillonnage perdue vers " + edge);
            }
        }
    }

    @Test
    void confirmationJourneyIsASingleTrace() {
        String traceId = randomTraceId();
        String parentSpanId = randomSpanId();
        CsrfTestPair.withCsrf().header(TRACEPARENT, "00-" + traceId + "-" + parentSpanId + "-01")
                .contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + CONFIRMATION_KEY + "\"}")
                .when().post("/api/v1/drafts/" + DRAFT_ID + "/confirmation")
                .then().statusCode(201);
        int downstreamCalls = outgoingTraceparents("subscription").size();
        assertEquals(1, downstreamCalls, "la confirmation fait un seul appel aval");

        List<SpanData> trace = awaitTrace(traceId, found -> clientSpans(found).size() >= downstreamCalls
                && found.stream().anyMatch(s -> s.getKind() == SpanKind.SERVER));

        List<SpanData> servers = trace.stream().filter(s -> s.getKind() == SpanKind.SERVER).toList();
        assertEquals(1, servers.size(), "un seul span passerelle : " + names(trace));
        SpanData server = servers.get(0);
        assertEquals(parentSpanId, server.getParentSpanId(), "le span racine adopte le parent recu");
        List<SpanData> journeys = trace.stream().filter(s -> s.getName().equals("confirm-journey")).toList();
        assertEquals(1, journeys.size(), names(trace));
        assertEquals(Journeys.CONFIRM, journeys.get(0).getAttributes().get(Journeys.JOURNEY));
        assertEquals(downstreamCalls, clientSpans(trace).size(), "un span par appel aval : " + names(trace));
        Set<String> traceIds = trace.stream().map(SpanData::getTraceId).collect(Collectors.toSet());
        assertEquals(Set.of(traceId), traceIds);
        // One tree: every span descends from the gateway server span.
        Set<String> ids = trace.stream().map(SpanData::getSpanId).collect(Collectors.toSet());
        trace.stream().filter(s -> s != server).forEach(s -> assertTrue(ids.contains(s.getParentSpanId()),
                "span detache de l'arbre de la passerelle : " + s.getName()));
        // The client span is the one whose id travelled downstream.
        String outgoing = outgoingTraceparents("subscription").get(0);
        assertEquals(clientSpans(trace).get(0).getSpanId(), outgoing.split("-")[2]);
    }

    @Test
    void theGatewayCreatesTheRootWhenTheCallerSendsNone() {
        given().when().get("/api/v1/products").then().statusCode(200);
        List<String> received = outgoingTraceparents("catalogue");
        assertEquals(1, received.size());
        String[] parts = received.get(0).split("-");
        assertNotEquals("00000000000000000000000000000000", parts[1], "trace racine creee par la passerelle");
        // Uniform ratio 0 and success: recorded, propagated unsampled, never exported.
        assertEquals("00", parts[3]);
        assertTrue(exportedAfterSettling(parts[1]).isEmpty(), "une reussite suit le ratio uniforme");
    }

    @Test
    void errorResponsesAreAlwaysExported() {
        DownstreamWireMocks.server("subscription").stubFor(WireMock.post(
                        WireMock.urlPathMatching("/v1/drafts/[^/]+/confirmation")).atPriority(1)
                .willReturn(WireMock.aResponse().withStatus(503)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503}")));
        int failures = 5;
        List<String> traceIds = new ArrayList<>();
        for (int i = 0; i < failures; i++) {
            // Alternately rootless and with an unsampled caller decision: neither may lose the error.
            var request = CsrfTestPair.withCsrf().contentType(ContentType.JSON)
                    .body("{\"confirmationKey\":\"" + CONFIRMATION_KEY + "\"}");
            if (i % 2 == 1) {
                request = request.header(TRACEPARENT, traceparent(randomTraceId(), false));
            }
            request.when().post("/api/v1/drafts/" + DRAFT_ID + "/confirmation").then().statusCode(503);
        }
        outgoingTraceparents("subscription").forEach(header -> traceIds.add(header.split("-")[1]));
        assertEquals(failures, traceIds.size());

        for (String traceId : traceIds) {
            List<SpanData> trace = awaitTrace(traceId, found -> found.stream()
                    .anyMatch(s -> s.getKind() == SpanKind.SERVER));
            SpanData server = trace.stream().filter(s -> s.getKind() == SpanKind.SERVER).findFirst().orElseThrow();
            assertEquals(503L, server.getAttributes().get(AttributeKey.longKey("http.response.status_code")));
            assertEquals(StatusCode.ERROR, server.getStatus().getStatusCode());
            assertTrue(trace.stream().anyMatch(s -> s.getKind() == SpanKind.CLIENT
                            && s.getStatus().getStatusCode() == StatusCode.ERROR),
                    "le span de l'appel aval en echec fait partie de la trace : " + names(trace));
            assertTrue(trace.stream().anyMatch(s -> s.getName().equals("confirm-journey")), names(trace));
        }
    }

    @Test
    void noPersonalDataInSpans() {
        String traceId = randomTraceId();
        String token = given().when().get("/api/v1/csrf-token").then().statusCode(200)
                .extract().jsonPath().getString("token");
        var withPair = given().cookie(config("gateway.csrf.cookie-name"), token)
                .header(config("gateway.csrf.header-name"), token)
                .header(TRACEPARENT, traceparent(traceId, true)).contentType(ContentType.JSON);
        withPair.body(SUBSCRIBER).when().put("/api/v1/drafts/" + DRAFT_ID + "/subscriber")
                .then().statusCode(200);
        given().cookie(config("gateway.csrf.cookie-name"), token)
                .header(config("gateway.csrf.header-name"), token)
                .header(TRACEPARENT, traceparent(traceId, true)).contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + CONFIRMATION_KEY + "\"}")
                .when().post("/api/v1/drafts/" + DRAFT_ID + "/confirmation").then().statusCode(201);
        // Invalid subscriber: the validation failure names the rejected field.
        given().cookie(config("gateway.csrf.cookie-name"), token)
                .header(config("gateway.csrf.header-name"), token)
                .header(TRACEPARENT, traceparent(traceId, true)).contentType(ContentType.JSON)
                .body(Map.of("firstName", "", "lastName", "Martin", "email", "camille@example.test",
                        "birthDate", "1980-01-01"))
                .when().put("/api/v1/drafts/" + DRAFT_ID + "/subscriber").then().statusCode(400);

        List<SpanData> trace = awaitTrace(traceId, found -> found.stream()
                .filter(s -> s.getKind() == SpanKind.SERVER).count() >= 3);
        Set<String> keys = new TreeSet<>();
        StringBuilder rendered = new StringBuilder();
        for (SpanData span : trace) {
            span.getAttributes().forEach((key, value) -> keys.add(key.getKey()));
            rendered.append(span.getName()).append(' ').append(span.getAttributes()).append(' ')
                    .append(span.getEvents()).append(' ').append(span.getStatus()).append('\n');
        }
        String all = rendered.toString();
        for (String forbidden : PERSONAL_VALUES) {
            assertFalse(all.contains(forbidden), "donnee personnelle dans un span : " + forbidden
                    + " in " + all.lines().filter(l -> l.contains(forbidden)).toList());
        }
        for (String forbidden : PERSONAL_FIELD_NAMES) {
            assertFalse(all.contains(forbidden), "champ personnel dans un span : " + forbidden
                    + " in " + all.lines().filter(l -> l.contains(forbidden)).toList());
        }
        assertFalse(all.contains(token), "jeton CSRF dans un span");
        assertFalse(all.contains(CONFIRMATION_KEY), "cle de confirmation dans un span");
        for (SpanData server : trace.stream().filter(sp -> sp.getKind() == SpanKind.SERVER).toList()) {
            assertFalse(server.getAttributes().get(AttributeKey.stringKey("url.path")).contains(DRAFT_ID),
                    "chemin brut dans le span serveur : " + server.getAttributes());
            assertEquals(SpanAttributeHygiene.REDACTED,
                    server.getAttributes().get(AttributeKey.stringKey("client.address")));
            assertTrue(server.getEvents().isEmpty(), "evenement d'exception sur le span serveur");
        }
        Set<String> unexpected = new TreeSet<>(keys);
        unexpected.removeAll(ALLOWED_ATTRIBUTES);
        assertTrue(unexpected.isEmpty(), "attribut hors liste autorisee : " + unexpected + " / " + keys);
        Set<String> journeys = trace.stream().map(s -> s.getAttributes().get(Journeys.JOURNEY))
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        assertTrue(Set.of(Journeys.CATALOGUE, Journeys.SIMULATION, Journeys.CONFIRM).containsAll(journeys));
    }

    @Test
    void logRecordsCarryTheIdsOfTheActiveSpan() throws IOException {
        LogHygieneTest.Capture capture = LogHygieneTest.Capture.ofConsole();
        org.jboss.logmanager.Logger root = org.jboss.logmanager.LogContext.getLogContext().getLogger("");
        java.util.logging.Handler[] current = root.getHandlers();
        java.util.logging.Handler[] withCapture = new java.util.logging.Handler[current.length + 1];
        withCapture[0] = capture;
        System.arraycopy(current, 0, withCapture, 1, current.length);
        root.setHandlers(withCapture);
        String traceId = randomTraceId();
        try {
            // A CSRF rejection writes exactly one WARN record while the request span is active.
            given().header(TRACEPARENT, traceparent(traceId, true)).contentType(ContentType.JSON)
                    .body(Map.of("productCode", "PRUDENT"))
                    .when().post("/api/v1/drafts").then().statusCode(403);
        } finally {
            root.removeHandler(capture);
        }
        List<SpanData> trace = awaitTrace(traceId, found -> found.stream()
                .anyMatch(s -> s.getKind() == SpanKind.SERVER));
        Set<String> spanIds = trace.stream().map(SpanData::getSpanId).collect(Collectors.toSet());

        List<JsonNode> records = new ArrayList<>();
        for (String line : capture.lines()) {
            JsonNode record = JSON.readTree(line);
            if (traceId.equals(record.path("mdc").path("traceId").asText())) {
                records.add(record);
            }
        }
        assertFalse(records.isEmpty(), "aucun enregistrement correle a la trace " + traceId);
        for (JsonNode record : records) {
            assertTrue(spanIds.contains(record.path("mdc").path("spanId").asText()),
                    "spanId du journal sans span correspondant : " + record);
        }
        assertTrue(records.stream().anyMatch(r -> r.path("message").asText().contains(" rejection reason=")));
    }

    private List<SpanData> awaitTrace(String traceId, Predicate<List<SpanData>> complete) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        List<SpanData> found = List.of();
        while (System.nanoTime() < deadline) {
            found = exported(traceId);
            if (!found.isEmpty() && complete.test(found)) {
                return found;
            }
            pause(50);
        }
        throw new AssertionError("trace " + traceId + " incomplete apres 10 s : " + names(found));
    }

    private List<SpanData> exportedAfterSettling(String traceId) {
        pause(500);
        return exported(traceId);
    }

    private List<SpanData> exported(String traceId) {
        return spans.getFinishedSpanItems().stream()
                .filter(s -> s.getTraceId().equals(traceId)).toList();
    }

    private static List<SpanData> clientSpans(List<SpanData> trace) {
        return trace.stream().filter(s -> s.getKind() == SpanKind.CLIENT).toList();
    }

    private static String names(List<SpanData> trace) {
        return trace.stream().map(s -> s.getKind() + ":" + s.getName()).toList().toString();
    }

    private static List<String> outgoingTraceparents(String edge) {
        WireMockServer server = DownstreamWireMocks.server(edge);
        return server.getAllServeEvents().stream()
                .map(event -> event.getRequest().getHeader(TRACEPARENT))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static String traceparent(String traceId, boolean sampled) {
        return "00-" + traceId + "-" + randomSpanId() + (sampled ? "-01" : "-00");
    }

    private static String randomTraceId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String randomSpanId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static String config(String key) {
        return org.eclipse.microprofile.config.ConfigProvider.getConfig().getValue(key, String.class);
    }
}
