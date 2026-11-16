package fr.demo.souscription.simulation.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * W4-T05 : les compteurs simulation.inputs.distinct / simulation.inputs.repeated sont
 * exportés, sans tag à cardinalité élevée, et classent correctement un trafic connu —
 * c'est la mesure qui fonde la décision « pas de cache » consignée dans le README.
 */
@QuarkusTest
class DuplicateInputRatioTest {

    /** /q/metrics a migré sur le port de management (wave 5) : le scrape n'est plus public. */
    @TestHTTPResource(value = "metrics", management = true)
    URL metricsUrl;


    private static final String BODY = "{\"initialAmount\":1000.00,\"monthlyAmount\":100.00,"
            + "\"durationYears\":%d,\"annualRate\":0.04}";

    private static void post(int durationYears) {
        given().contentType(ContentType.JSON)
            .body(String.format(BODY, durationYears))
        .when().post("/v1/simulations")
        .then().statusCode(200);
    }

    private String metrics() {
        return RestAssured.get(metricsUrl).then().statusCode(200).extract().asString();
    }

    private static double counter(String metrics, String prometheusName) {
        // Le scrape OpenMetrics peut suffixer un exemplar (« # {span_id=...} 1.0 <ts> ») :
        // il n'est pas un tag de la série, d'où le groupe optionnel final.
        Matcher matcher = Pattern.compile("^" + Pattern.quote(prometheusName)
                                + "(\\{[^}]*\\})?\\s+([0-9.eE+-]+)(\\s+#.*)?$",
                        Pattern.MULTILINE)
                .matcher(metrics);
        assertTrue(matcher.find(), prometheusName + " is not exported:\n" + metrics);
        String tags = matcher.group(1);
        // Aucun tag à cardinalité élevée : ni montant, ni taux, ni corrélation id.
        assertTrue(tags == null || tags.equals("{}"), prometheusName + " carries tags: " + tags);
        return Double.parseDouble(matcher.group(2));
    }

    @Test
    void classifiesDistinctAndRepeatedInputsWithoutHighCardinalityTags() {
        // Amorce les deux compteurs (ils ne sont visibles qu'après un premier incrément).
        post(3);
        post(3);

        String before = metrics();
        double distinctBefore = counter(before, "simulation_inputs_distinct_total");
        double repeatedBefore = counter(before, "simulation_inputs_repeated_total");

        post(11);
        post(12);
        post(11);
        post(12);
        post(11);

        String after = metrics();
        double distinctTotal = counter(after, "simulation_inputs_distinct_total");
        double repeatedTotal = counter(after, "simulation_inputs_repeated_total");
        // Figure reprise dans le README (décision de cache) : ratio observé sur le trafic
        // de la suite de tests, tous scénarios confondus.
        System.out.printf("duplicate-input-ratio distinct=%.0f repeated=%.0f ratio=%.3f%n",
                distinctTotal, repeatedTotal, repeatedTotal / (distinctTotal + repeatedTotal));
        assertEquals(2.0d, distinctTotal - distinctBefore,
                "two never-seen inputs must count as distinct");
        assertEquals(3.0d, repeatedTotal - repeatedBefore,
                "three re-submissions of a seen input must count as repeated");
    }

    @Test
    void exportsNoCacheMetricsBecauseNoCacheIsEnabled() {
        post(7);
        String metrics = metrics();
        assertFalse(metrics.contains("cache_gets"), "no cache is enabled on the computation");
    }
}
