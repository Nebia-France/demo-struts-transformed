package fr.demo.souscription.health;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.observability.StartupObserver;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresDevServicesProfile;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parity skeleton for 25-souscription-service-health-probes.
 *
 * Baseline reference: BASELINE.md section 4.8 — the source had no health endpoint and shut down
 * abruptly from the web context destruction callback. New capability (NFG-012), so the anchor is
 * the documented target; the three rewritten Gherkin scenarios of
 * features/initialisation-application are the behavioural trace.
 *
 * The database-down cases live in a SEPARATE class (DatabaseDownReadinessParityTest) because a
 * Quarkus test profile applies per test class: a nested class cannot carry a different profile.
 *
 * <p>Adapted to this module's harness: the all-UP cases need a real datasource (the module's global
 * test profile leaves the pool inactive), so they run under the shared PostgreSQL Dev Services
 * profile and carry the container-runtime guard used by the other integration tests. The startup
 * log line assertion needs no container and therefore lives in its own class
 * ({@code StartupLineParityTest}) so it is never skipped with the container-backed cases.
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class HealthProbeParityTest {

    @Test
    void allThreeProbesAreUp() {
        // Scenario: "Demarrage nominal de l'application".
        given().when().get("/q/health/started").then().statusCode(200).body("status", is("UP"));
        given().when().get("/q/health/live").then().statusCode(200).body("status", is("UP"));
        given().when().get("/q/health/ready").then().statusCode(200)
                .body("status", is("UP"))
                .body(containsString("database"));
    }

    @Test
    void readinessPayloadNamesItsChecks() {
        given().when().get("/q/health/ready").then().statusCode(200)
                .body("checks.name", org.hamcrest.Matchers.hasItem("database"));
    }
}

/**
 * The startup log line — the target form of the scenario {@code Demarrage nominal de
 * l'application} and the only boot-time evidence that the schema and pool configuration resolved.
 *
 * <p>The line is emitted at boot, before any JUnit callback can attach a handler, so the observer
 * is exercised directly with the capture handler of {@code CorrelatedLoggingParityTest} installed
 * and with the two configuration values resolved from the application configuration (not
 * hard-coded): what is asserted is both the format of the line and the fact that the schema and
 * pool properties resolve. A second {@code @QuarkusTest} application is deliberately NOT booted —
 * the probe assertions above already own that — because a second boot competes for the test HTTP
 * port.
 */
class StartupLineParityTest {

    private final List<LogRecord> captured = new CopyOnWriteArrayList<>();
    private Handler handler;

    @BeforeEach
    void attachHandler() {
        captured.clear();
        handler = new Handler() {
            @Override public void publish(LogRecord record) {
                captured.add(record);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger.getLogger("fr.demo.souscription").addHandler(handler);
        Logger.getLogger("fr.demo.souscription").setLevel(java.util.logging.Level.INFO);
    }

    @AfterEach
    void detachHandler() {
        Logger.getLogger("fr.demo.souscription").removeHandler(handler);
    }

    @Test
    void startupLineIsEmitted() throws Exception {
        Config config = ConfigProvider.getConfig();
        String schema = config.getValue("quarkus.hibernate-orm.database.default-schema", String.class);
        int poolMax = config.getValue("quarkus.datasource.jdbc.max-size", Integer.class);

        StartupObserver observer = new StartupObserver();
        set(observer, "schema", schema);
        set(observer, "poolMax", poolMax);

        Method onStart = StartupObserver.class.getDeclaredMethod("onStart", StartupEvent.class);
        onStart.setAccessible(true);
        onStart.invoke(observer, new StartupEvent());

        List<String> lines = captured.stream().map(StartupLineParityTest::formatted).toList();

        assertTrue(lines.stream().anyMatch(line ->
                        line.contains("schema=" + schema) && line.contains("pool=" + poolMax)),
                "the startup line must name the schema and the pool size: it is the only boot-time "
                + "evidence that the configuration applied. Seen: " + lines);
        assertFalse(lines.stream().anyMatch(line -> line.contains("jdbc:") || line.contains("password")),
                "the startup line must not carry a JDBC URL or any credential. Seen: " + lines);
    }

    private static void set(StartupObserver observer, String field, Object value) throws Exception {
        Field target = StartupObserver.class.getDeclaredField(field);
        target.setAccessible(true);
        target.set(observer, value);
    }

    private static String formatted(LogRecord record) {
        Object[] parameters = record.getParameters();
        String message = record.getMessage();
        if (parameters == null || parameters.length == 0) {
            return message;
        }
        return String.format(message, parameters);
    }
}
