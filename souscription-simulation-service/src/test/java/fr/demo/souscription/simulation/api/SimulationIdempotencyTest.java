package fr.demo.souscription.simulation.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * W6-T04: the computation is idempotent, and that is <em>asserted</em> rather than assumed.
 *
 * <p>Both frozen resilience contracts allow the caller a single retry on {@code 502/503/504}. That
 * allowance is only sound if replaying the very same request body cannot produce a different answer
 * and cannot leave a trace: the retried call must be indistinguishable from the first. The
 * instrumentation added in waves 4 and 5 (input-digest counters, business timer, audit log, spans)
 * all observes the request, so this suite also pins that none of it leaks into the payload - a
 * counter that appeared in the response would silently make the N-th answer differ from the first.
 */
@QuarkusTest
@Tag("contract")
class SimulationIdempotencyTest {

    /** The skill's N: ten replays of the same body, well beyond the single contracted retry. */
    private static final int REPLAYS = 10;

    /** Worst case of the published budget: durationYears = 30, i.e. 360 DECIMAL128 iterations. */
    private static final String BODY = "{\"initialAmount\":100000.00,\"monthlyAmount\":2000.00,"
            + "\"durationYears\":30,\"annualRate\":0.060000}";

    @TestHTTPResource(value = "metrics", management = true)
    URL metricsUrl;

    @Test
    void tenIdenticalRequestsYieldByteIdenticalBodies() {
        List<String> bodies = IntStream.range(0, REPLAYS).mapToObj(i -> post()).toList();

        Set<String> distinct = new LinkedHashSet<>(bodies);
        assertEquals(1, distinct.size(),
                () -> REPLAYS + " replays of the same request produced " + distinct.size()
                        + " different bodies, so the contracted single retry is not sound: "
                        + distinct);
        // Explicit on the N-th: the instrumentation of waves 4 and 5 counts every call, so a
        // counter reaching the payload would show up here and nowhere else.
        assertEquals(bodies.get(0), bodies.get(REPLAYS - 1));
    }

    @Test
    void aReplayCarriesNoStateInTheResponseEnvelope() {
        var first = given().contentType(ContentType.JSON).body(BODY)
                .when().post("/v1/simulations").then().statusCode(200).extract();
        var last = given().contentType(ContentType.JSON).body(BODY)
                .when().post("/v1/simulations").then().statusCode(200).extract();

        assertEquals(first.asString(), last.asString());
        assertEquals(first.contentType(), last.contentType());
        // No cookie, no ETag, no Location: nothing that would let a replay be routed or resolved
        // differently from the first call.
        assertNull(last.header("Set-Cookie"), "a stateless computation must not open a session");
        assertNull(last.header("Location"), "nothing is created, so there is nothing to locate");
    }

    @Test
    void replaysMutateNoBusinessCounterBeyondTheCallCount() {
        // The only legitimate movement is the request-counting instrumentation itself. A business
        // quantity - a created object, a sequence, a persisted total - must not exist at all: this
        // service owns no datastore, so the proof is that no business counter is exported.
        post();
        String metrics = RestAssured.get(metricsUrl).then().statusCode(200).extract().asString();

        double callsBefore = counter(metrics, "simulation_calculate_seconds_count");
        IntStream.range(0, REPLAYS).forEach(i -> post());
        String after = RestAssured.get(metricsUrl).then().statusCode(200).extract().asString();

        assertEquals(callsBefore + REPLAYS, counter(after, "simulation_calculate_seconds_count"),
                "the business timer counts calls, nothing else");
        assertTrue(after.lines().noneMatch(line ->
                        line.startsWith("simulation_subscriptions") || line.startsWith("simulation_persisted")),
                "no persisted or created business quantity may exist in a side-effect free service");
    }

    private static String post() {
        return given().contentType(ContentType.JSON).body(BODY)
                .when().post("/v1/simulations")
                .then().statusCode(200)
                .extract().asString();
    }

    private static double counter(String metrics, String prometheusName) {
        Matcher matcher = Pattern.compile("^" + Pattern.quote(prometheusName)
                                + "(\\{[^}]*\\})?\\s+([0-9.eE+-]+)(\\s+#.*)?$", Pattern.MULTILINE)
                .matcher(metrics);
        assertTrue(matcher.find(), prometheusName + " is not exported:\n" + metrics);
        return Double.parseDouble(matcher.group(2));
    }
}
