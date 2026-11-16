package fr.demo.souscription.simulation.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import fr.demo.souscription.simulation.service.SimulationService;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.net.URL;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Parity check 19 : le dimensionnement est mesuré et la décision de cache est
 * cohérente avec le code. Les deux états finaux sont acceptables (cache activé borné,
 * ou pas de cache) : ce test vérifie la cohérence interne de l'état choisi, le
 * caractère borné du suivi des doublons et l'absence de tag à cardinalité élevée.
 */
@QuarkusTest
class SizingCacheDecisionParityTest {

    /** /q/metrics a migré sur le port de management (wave 5) : le scrape n'est plus public. */
    @TestHTTPResource(value = "metrics", management = true)
    URL metricsUrl;


    private static final String BODY = "{\"initialAmount\":1000.00,\"monthlyAmount\":100.00,"
            + "\"durationYears\":9,\"annualRate\":0.04}";

    @Inject
    SimulationInputDigestMeter meter;

    private String metrics() {
        return RestAssured.get(metricsUrl).then().statusCode(200).extract().asString();
    }

    private static boolean cacheIsConfigured() {
        for (Method method : SimulationService.class.getDeclaredMethods()) {
            for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
                if (annotation.annotationType().getName().contains("CacheResult")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void duplicateCountersAreRegisteredWithoutHighCardinalityTag() {
        given().contentType(ContentType.JSON).body(BODY)
            .when().post("/v1/simulations").then().statusCode(200);

        String metrics = metrics();
        for (String name : new String[] {"simulation_inputs_distinct_total",
                                         "simulation_inputs_repeated_total"}) {
            Matcher matcher = Pattern.compile("^" + Pattern.quote(name)
                            + "(\\{[^}]*\\})?\\s+([0-9.eE+-]+)(\\s+#.*)?$", Pattern.MULTILINE)
                    .matcher(metrics);
            assertTrue(matcher.find(), name + " is not registered:\n" + metrics);
            String tags = matcher.group(1);
            assertTrue(tags == null || tags.equals("{}"),
                    name + " carries a tag (amount / rate / correlation id are forbidden"
                            + " — unbounded cardinality): " + tags);
        }
    }

    @Test
    void duplicateTrackingStructureIsBounded() throws Exception {
        Field capacity = SimulationInputDigestMeter.class.getDeclaredField("WINDOW");
        capacity.setAccessible(true);
        int window = (int) capacity.get(null);
        assertTrue(window > 0, "the recent-request digest set must have a fixed capacity");

        Field recentField = SimulationInputDigestMeter.class.getDeclaredField("recent");
        recentField.setAccessible(true);
        // Le bean est @ApplicationScoped : lire le champ sur le proxy client renvoie null.
        Object instance = io.quarkus.arc.ClientProxy.unwrap(meter);
        Map<?, ?> recent = (Map<?, ?>) recentField.get(instance);
        // Le suivi ne stocke pas les valeurs métier : clés = empreintes entières.
        assertTrue(Map.class.isAssignableFrom(recentField.getType()),
                "digest tracking must be a keyed structure of digests, not of raw amounts");

        for (int i = 0; i < window * 3; i++) {
            meter.record(new SimulationRequest(BigDecimal.valueOf(1000 + i),
                    BigDecimal.valueOf(100), 9, new BigDecimal("0.04")));
        }
        assertTrue(recent.size() <= window,
                "the recent-request digest set grew past its capacity (" + recent.size()
                        + " > " + window + ") — unbounded tracking is a memory leak");
        for (Object key : recent.keySet()) {
            assertFalse(key instanceof BigDecimal,
                    "digests must not be the raw monetary values");
        }
    }

    @Test
    void cacheIfEnabledReturnsIdenticalBytes() {
        assumeTrue(cacheIsConfigured(),
                "no cache is configured on the computation — step skipped by design");

        String first = given().contentType(ContentType.JSON).body(BODY)
            .when().post("/v1/simulations").then().statusCode(200).extract().asString();
        String second = given().contentType(ContentType.JSON).body(BODY)
            .when().post("/v1/simulations").then().statusCode(200).extract().asString();
        assertTrue(first.equals(second),
                "the cached response differs from the first one: the cache key is incomplete"
                        + " or the cached object was mutated\n" + first + "\n" + second);
    }
}
