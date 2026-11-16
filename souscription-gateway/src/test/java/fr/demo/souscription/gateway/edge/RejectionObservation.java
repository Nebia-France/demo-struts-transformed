package fr.demo.souscription.gateway.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.demo.souscription.gateway.error.ProblemResponses;
import fr.demo.souscription.gateway.observability.RejectionMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Filter;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import org.jboss.logmanager.ExtHandler;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.LogContext;
import org.jboss.logmanager.Logger;

/**
 * Observes what an edge rejection leaves behind besides its status (W7-T02): the
 * {@code gateway.rejections} counter of plan 26 and the single structured log entry of plan 27.
 *
 * <p>Installed in front of the root handlers, it renders every {@link RejectionMetrics} record with
 * the formatter of the configured console handler, so a captured entry is the JSON line written to
 * stdout. A negative test then asserts that the matching series moved by exactly one, that every
 * other series stayed put, and that exactly one rejection entry carries the problem
 * {@code instance} as its {@code requestId}.
 */
final class RejectionObservation extends ExtHandler {

    private static final String METRIC = "gateway.rejections";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MeterRegistry registry;

    private final Formatter formatter;

    private final List<Filter> filters;

    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

    private final List<String> problemLines = Collections.synchronizedList(new ArrayList<>());

    private double totalBefore;

    private RejectionObservation(MeterRegistry registry, List<Handler> consolePath) {
        this.registry = registry;
        this.formatter = consolePath.get(consolePath.size() - 1).getFormatter();
        this.filters = consolePath.stream().map(Handler::getFilter)
                .filter(java.util.Objects::nonNull).toList();
    }

    /** Starts observing; call {@link #stop()} once the test is over. */
    static RejectionObservation start(MeterRegistry registry) {
        RejectionObservation observation = new RejectionObservation(registry, consolePath());
        Logger root = root();
        Handler[] current = root.getHandlers();
        Handler[] withCapture = new Handler[current.length + 1];
        withCapture[0] = observation;
        System.arraycopy(current, 0, withCapture, 1, current.length);
        root.setHandlers(withCapture);
        observation.totalBefore = observation.total();
        return observation;
    }

    void stop() {
        root().removeHandler(this);
    }

    /**
     * Sends one request in its own observation window and asserts it is a single edge rejection
     * of the given series: status, counter moved by one, one correlated rejection entry.
     */
    static io.restassured.response.Response rejectedOnce(MeterRegistry registry, int status,
            String reason, Supplier<io.restassured.response.Response> request) {
        RejectionObservation observation = start(registry);
        try {
            double before = observation.count(status, reason);
            io.restassured.response.Response response = request.get();
            assertEquals(status, response.getStatusCode(), "unexpected status: "
                    + response.getBody().asString());
            observation.assertSingleRejection(status, reason, before,
                    response.jsonPath().getString("instance"));
            return response;
        } finally {
            observation.stop();
        }
    }

    /** Value of one {@code gateway_rejections_total{status, reason}} series. */
    double count(int status, String reason) {
        Counter counter = registry.find(METRIC)
                .tag("status", String.valueOf(status)).tag("reason", reason).counter();
        assertTrue(counter != null, "series " + status + "/" + reason + " must be pre-registered");
        return counter.count();
    }

    private double total() {
        return registry.find(METRIC).counters().stream().mapToDouble(Counter::count).sum();
    }

    /**
     * Asserts the observed requests produced exactly one rejection, counted in the given series and
     * logged once as a JSON entry whose {@code requestId} is the problem {@code instance}.
     */
    void assertSingleRejection(int status, String reason, double seriesBefore, String instance) {
        assertEquals(seriesBefore + 1, count(status, reason), 0.0001d,
                "gateway.rejections{status=" + status + ",reason=" + reason + "} must move by one");
        assertEquals(totalBefore + 1, total(), 0.0001d, "no other rejection series may move");
        List<JsonNode> entries = entries();
        assertEquals(1, entries.size(), "exactly one structured rejection entry: " + lines());
        JsonNode entry = entries.get(0);
        assertEquals("WARN", entry.path("level").asText());
        assertEquals(instance, entry.path("mdc").path("requestId").asText(),
                "the entry must be correlated with the problem instance");
        assertTrue(entry.path("message").asText().contains("reason=" + reason + " status=" + status),
                "the entry must name the closed-set reason: " + entry);
    }

    /** Asserts nothing was counted nor logged as an edge rejection. */
    void assertNoRejection() {
        assertEquals(totalBefore, total(), 0.0001d, "no rejection series may move");
        assertEquals(0, entries().size(), "no rejection entry expected: " + lines());
    }

    /**
     * Asserts a failure decided past the edge filters (resource-level 405, 400, 404, 409, downstream
     * 502/503): it belongs to no {@code gateway.rejections} series, whose closed set only covers
     * edge rejections (plan 26), so no series may move and no rejection entry may be written; it is
     * journalised instead as exactly one {@code ProblemResponses} JSON entry (plan 27), correlated
     * with the problem {@code instance} and naming its public status.
     */
    void assertSingleProblemEntry(int status, String instance) {
        assertNoRejection();
        List<JsonNode> entries = parse(problemLines());
        assertEquals(1, entries.size(), "exactly one structured problem entry: " + problemLines());
        JsonNode entry = entries.get(0);
        assertEquals(status >= 500 ? "ERROR" : "DEBUG", entry.path("level").asText());
        assertEquals(instance, entry.path("mdc").path("requestId").asText(),
                "the entry must be correlated with the problem instance");
        assertTrue(entry.path("message").asText().contains("status=" + status),
                "the entry must name the public status: " + entry);
    }

    private List<JsonNode> entries() {
        return parse(lines());
    }

    private static List<JsonNode> parse(List<String> source) {
        List<JsonNode> entries = new ArrayList<>();
        for (String line : source) {
            try {
                entries.add(JSON.readTree(line));
            } catch (IOException e) {
                fail("entry is not JSON: " + line);
            }
        }
        return entries;
    }

    private List<String> lines() {
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }

    private List<String> problemLines() {
        synchronized (problemLines) {
            return List.copyOf(problemLines);
        }
    }

    @Override
    protected void doPublish(ExtLogRecord record) {
        List<String> target;
        if (RejectionMetrics.class.getName().equals(record.getLoggerName())) {
            target = lines;
        } else if (ProblemResponses.class.getName().equals(record.getLoggerName())) {
            target = problemLines;
        } else {
            return;
        }
        for (Filter filter : filters) {
            if (!filter.isLoggable(record)) {
                return;
            }
        }
        for (String line : formatter.format(record).split("\\R")) {
            if (!line.isBlank()) {
                target.add(line);
            }
        }
    }

    private static Logger root() {
        return LogContext.getLogContext().getLogger("");
    }

    /** Handler chain from the root down to the console handler, whose filters all apply. */
    private static List<Handler> consolePath() {
        List<Handler> path = new ArrayList<>();
        if (!findConsole(root().getHandlers(), path)) {
            fail("console handler not found");
        }
        return path;
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
}
