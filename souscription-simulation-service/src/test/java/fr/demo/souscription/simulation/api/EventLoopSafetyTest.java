package fr.demo.souscription.simulation.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * W4-T01 : la projection, CPU-bound (360 itérations DECIMAL128 pour durationYears = 30),
 * est dispatchée sur un thread worker par {@code @Blocking}. Une rafale de requêtes
 * pire-cas concurrentes ne doit produire AUCUN avertissement du BlockedThreadChecker
 * de Vert.x (quarkus.vertx.max-event-loop-execute-time=2S).
 */
@QuarkusTest
class EventLoopSafetyTest {

    /** Pire cas : durationYears = 30 => 360 itérations BigDecimal DECIMAL128. */
    private static final String WORST_CASE_BODY =
        "{\"initialAmount\":100000.00,\"monthlyAmount\":2000.00,\"durationYears\":30,\"annualRate\":0.10}";

    private static final int BURST = 64;
    private static final int CONCURRENCY = 16;

    private final List<String> blockedWarnings = new CopyOnWriteArrayList<>();
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
                if (message.contains("has been blocked for")
                        || logger.contains("BlockedThreadChecker")
                        || message.contains("BlockedThreadChecker")) {
                    blockedWarnings.add(logger + " :: " + message);
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

    @Test
    void aWorstCaseBurstNeverBlocksTheEventLoop() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<Callable<Integer>> calls = IntStream.range(0, BURST)
                .<Callable<Integer>>mapToObj(i -> () -> given().contentType(ContentType.JSON)
                    .body(WORST_CASE_BODY)
                    .when().post("/v1/simulations")
                    .then().extract().statusCode())
                .collect(Collectors.toList());
            List<Future<Integer>> results = pool.invokeAll(calls);
            for (Future<Integer> result : results) {
                assertEquals(200, result.get().intValue(), "every worst-case request must succeed");
            }
        } finally {
            pool.shutdownNow();
        }

        // Laisse au checker (période de 1 s) le temps de signaler un blocage éventuel.
        Thread.sleep(2500L);

        assertTrue(blockedWarnings.isEmpty(),
            () -> "the event loop must never run the projection, got: " + blockedWarnings);
    }
}
