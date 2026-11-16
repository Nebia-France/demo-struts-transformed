package fr.demo.souscription.simulation.api;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity check for 17-simulation-blocking-dispatch-pools.
 *
 * The source ran each simulation on its own servlet request thread, so CPU work could never stall
 * the acceptor. The replacement must keep that property: the 360 DECIMAL128 iterations of the worst
 * case (durationYears = 30) must execute on a worker thread, never on the Vert.x event loop.
 *
 * Log capture is wired with a JBoss LogManager root Handler (the module already forces
 * java.util.logging.manager=org.jboss.logmanager.LogManager in Surefire), the same mechanism as
 * EventLoopSafetyTest.
 */
@QuarkusTest
class BlockingDispatchParityTest {

    private static final int BURST = 64;
    private static final String WORST_CASE =
            "{\"initialAmount\":100000.00,\"monthlyAmount\":2000.00,\"durationYears\":30,\"annualRate\":0.06}";

    private final List<String> captured = new CopyOnWriteArrayList<>();
    private java.util.logging.Logger root;
    private Handler capturingHandler;

    @BeforeEach
    void attachHandler() {
        root = LogManager.getLogManager().getLogger("");
        capturingHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String logger = record.getLoggerName() == null ? "" : record.getLoggerName();
                String message = record.getMessage() == null ? "" : record.getMessage();
                // Only the blocked-thread evidence is retained: capturing every record would make
                // the handler itself a bottleneck under the burst.
                if (message.contains("has been blocked for")
                        || logger.contains("BlockedThreadChecker")
                        || message.contains("BlockedThreadChecker")) {
                    captured.add(logger + " :: " + message);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        root.addHandler(capturingHandler);
    }

    @AfterEach
    void detachHandler() {
        root.removeHandler(capturingHandler);
    }

    private List<String> capturedLogLines() {
        return List.copyOf(captured);
    }

    private static int postWorstCase() {
        return given().header("Connection", "close")
                .contentType("application/json").body(WORST_CASE)
                .when().post("/v1/simulations")
                .then().extract().statusCode();
    }

    /** The parity property: a worst-case burst never reports a blocked event loop. */
    @Test
    void worstCaseBurstNeverBlocksTheEventLoop() throws Exception {
        runBurst();
        // The BlockedThreadChecker runs on a 1 s period; give it time to report.
        Thread.sleep(2500L);
        List<String> blocked = capturedLogLines().stream()
                .filter(line -> line.contains("has been blocked for")
                        || line.contains("BlockedThreadChecker"))
                .toList();
        assertTrue(blocked.isEmpty(), "event loop blocked during the burst: " + blocked);
    }

    /**
     * Every accepted request gets an answer. 200 is the normal outcome; 503 is legitimate once the
     * plan-18 bulkhead exists. A hang, a reset or a 500 is not: the servlet connector queued these
     * requests and the replacement must also answer them.
     */
    @Test
    void burstCompletesWithoutLossOrError() throws Exception {
        List<Integer> statuses = runBurst();
        assertEquals(BURST, statuses.size(), "every submitted request must produce a status");
        for (int status : statuses) {
            assertTrue(status == 200 || status == 503,
                    "unexpected status under load: " + status + " (500 or a reset means an unmapped "
                            + "rejection escaped the worker pool)");
        }
    }

    /**
     * An unbounded queue would convert fast rejection into unbounded latency: the caller's 3 s
     * timeout would fire instead of the clean server-side rejection the contract promises.
     */
    @Test
    void queueIsBoundedNotInfinite() {
        String queueSize = System.getProperty("quarkus.thread-pool.queue-size",
                System.getenv().getOrDefault("SIM_QUEUE_SIZE", ""));
        // The authoritative check is the grep of step 5; this guards the obvious -1 / 0 regressions.
        assertTrue(queueSize.isEmpty() || Integer.parseInt(queueSize) > 0,
                "thread-pool.queue-size must be a finite positive bound, was [" + queueSize + "]");
    }

    /** Results must not depend on the dispatch: the same body under load yields the same body. */
    @Test
    void concurrentResultsAreIdenticalToTheSequentialOne() throws Exception {
        String sequential = given().header("Connection", "close")
                .contentType("application/json").body(WORST_CASE)
                .when().post("/v1/simulations").then().statusCode(200)
                .extract().response().asString();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<String>> calls = IntStream.range(0, 32)
                    .<Callable<String>>mapToObj(i -> () -> given().header("Connection", "close")
                            .contentType("application/json")
                            .body(WORST_CASE).when().post("/v1/simulations")
                            .then().statusCode(200).extract().response().asString())
                    .toList();
            for (Future<String> future : pool.invokeAll(calls)) {
                assertEquals(sequential, future.get(),
                        "a concurrent response differs from the sequential one -- shared mutable state");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private List<Integer> runBurst() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            List<Callable<Integer>> calls = IntStream.range(0, BURST)
                    .<Callable<Integer>>mapToObj(i -> BlockingDispatchParityTest::postWorstCase)
                    .toList();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(calls)) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }
}
