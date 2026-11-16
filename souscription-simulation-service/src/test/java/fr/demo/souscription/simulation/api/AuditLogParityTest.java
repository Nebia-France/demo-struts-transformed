package fr.demo.souscription.simulation.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity check for 15-simulation-audit-logging.
 *
 * The monolith had NO simulation audit trail, so this is a new-capability check. The parity risk is
 * inverted: the new line must not leak personal data and must not alter the computation.
 *
 * Log capture: a JBoss LogManager handler installed on the root logger, formatting each record with
 * the same JSON formatter class Quarkus mounts on the console when quarkus.log.console.json=true
 * (the console handler itself lives in the application log context and cannot be read back).
 */
@QuarkusTest
class AuditLogParityTest {

    private static final String NOMINAL = """
            {"initialAmount":1200,"monthlyAmount":100,"durationYears":1,"annualRate":0}
            """;

    private static final Pattern PERSONAL = Pattern.compile(
            "(?i)firstName|lastName|email|birthDate|prenom|pr\u00e9nom|\"nom\"|iban|telephone");

    private static final Pattern REQUEST_DUMP = Pattern.compile(
            "(?i)authorization|set-cookie|\"cookie\"|user-agent|CN=|x509");

    private static final List<String> CAPTURED = Collections.synchronizedList(new ArrayList<>());

    private java.util.logging.Logger root;
    private Handler capturingHandler;

    @BeforeEach
    void attachHandler() {
        Formatter jsonFormatter = new org.jboss.logmanager.formatters.JsonFormatter();
        root = LogManager.getLogManager().getLogger("");
        capturingHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                CAPTURED.add(jsonFormatter.format(record));
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
        drain();
    }

    @Test
    void oneAuditLinePerProjection() {
        drain();
        post(NOMINAL);
        assertEquals(1, auditLines().size(), "exactly one projection audit line per successful request");

        drain();
        post(NOMINAL);
        post(NOMINAL);
        assertEquals(2, auditLines().size(), "a duplicated line would double-count every simulation");
    }

    @Test
    void everyEmittedLineIsValidJson() {
        drain();
        post(NOMINAL);
        for (String line : capturedLines()) {
            assertTrue(line.trim().startsWith("{") && line.trim().endsWith("}"),
                    "every log line must be a JSON object, got: " + line);
        }
    }

    @Test
    void auditLineCarriesTheContractedContent() {
        drain();
        post(NOMINAL);
        String line = auditLines().get(0);
        for (String key : new String[] {"initialAmount", "monthlyAmount", "durationYears",
                                        "annualRate", "contributed", "finalCapital"}) {
            assertTrue(line.contains(key), "audit line must carry " + key + ", got: " + line);
        }
        assertTrue(line.contains("timestamp") || line.contains("@timestamp") || line.contains("\"ts\""),
                "audit line must carry a timestamp, got: " + line);
    }

    @Test
    void auditLineCarriesCorrelationIds() {
        drain();
        post(NOMINAL);
        String line = auditLines().get(0);
        assertTrue(line.contains("traceId") || line.contains("trace_id"),
                "100% of request lines must carry traceId, got: " + line);
        assertTrue(line.contains("spanId") || line.contains("span_id"),
                "100% of request lines must carry spanId, got: " + line);
    }

    @Test
    void noPersonalDataFields() {
        drain();
        post(NOMINAL);
        for (String line : capturedLines()) {
            assertFalse(PERSONAL.matcher(line).find(),
                    "this service never receives personal data; a match means a payload is dumped: " + line);
        }
    }

    @Test
    void noRequestDumpOrCredentialMaterial() {
        drain();
        given().contentType("application/json")
                .header("Authorization", "Bearer must-not-be-logged")
                .header("Cookie", "session=must-not-be-logged")
                .body(NOMINAL)
                .when().post("/v1/simulations").then().statusCode(200);
        for (String line : capturedLines()) {
            assertFalse(REQUEST_DUMP.matcher(line).find(),
                    "headers, cookies and certificate subjects must never be logged: " + line);
            assertFalse(line.contains("must-not-be-logged"), "a header value reached the log: " + line);
        }
    }

    /** The logged amount must be the same plain decimal string the consumer received. */
    @Test
    void loggedAmountsMatchTheWireRepresentation() {
        drain();
        String responseBody = given().contentType("application/json").body(NOMINAL)
                .when().post("/v1/simulations").then().statusCode(200).extract().asString();
        assertTrue(responseBody.contains("2400.00"));

        String line = auditLines().get(0);
        assertTrue(line.contains("2400.00"),
                "the audit trail must agree with the wire value (plain, scale 2): " + line);
        assertFalse(auditedAmounts(line).matches(".*[eE][+-]?[0-9].*"),
                "no scientific notation in the audit line: " + line);
    }

    /** A rejected request must not be counted as a projection. */
    @Test
    void rejectedRequestsDoNotCountAsProjections() {
        drain();
        given().contentType("application/json")
                .body("""
                      {"initialAmount":99,"monthlyAmount":0,"durationYears":1,"annualRate":0}
                      """)
                .when().post("/v1/simulations").then().statusCode(400);
        assertEquals(0, auditLines().size(),
                "a 400 computes nothing; counting it as a projection corrupts every downstream report");
    }

    private static void post(String body) {
        given().contentType("application/json").body(body)
                .when().post("/v1/simulations").then().statusCode(200);
    }

    /** Projection audit lines only: the projection event carries the computed capital. */
    private static List<String> auditLines() {
        return capturedLines().stream()
                .filter(l -> l.contains("finalCapital"))
                .collect(Collectors.toList());
    }

    /** The amount fields of an audit line, isolated from the formatter's own metadata. */
    private static String auditedAmounts(String line) {
        return java.util.Arrays.stream(line.split("[,{}]"))
                .filter(f -> f.contains("initialAmount") || f.contains("monthlyAmount")
                        || f.contains("annualRate") || f.contains("contributed")
                        || f.contains("finalCapital"))
                .collect(Collectors.joining(" "));
    }

    // --- log capture adapter --------------------------------------------------------------------

    private static List<String> capturedLines() {
        synchronized (CAPTURED) {
            return new ArrayList<>(CAPTURED);
        }
    }

    private static void drain() {
        CAPTURED.clear();
    }
}
