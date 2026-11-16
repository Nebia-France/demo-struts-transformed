package fr.demo.souscription.simulation.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Parité du plan 11 : la suppression de la HttpSession est une relocation vers
 * souscription-service (table de brouillon persistée), pas une perte de fonctionnalité.
 * Ce test prouve l'absence d'état côté service : réponses byte-identiques, aucun
 * Set-Cookie, aucune interférence entre requêtes concurrentes.
 */
@QuarkusTest
class StatelessnessParityTest {

    private static final String BODY_A =
        "{\"initialAmount\":1000.00,\"monthlyAmount\":100.00,\"durationYears\":5,\"annualRate\":0.04}";
    private static final String BODY_B =
        "{\"initialAmount\":100.00,\"monthlyAmount\":0.00,\"durationYears\":1,\"annualRate\":0.00}";

    @Test
    void repeatedIdenticalRequestsAreByteIdentical() {
        byte[] first = post(BODY_A).asByteArray();
        byte[] second = post(BODY_A).asByteArray();
        assertArrayEquals(first, second, "repeated identical requests must be byte-identical");
    }

    @Test
    void anInterleavedDifferentRequestDoesNotChangeTheResult() {
        byte[] before = post(BODY_A).asByteArray();
        post(BODY_B);
        byte[] after = post(BODY_A).asByteArray();
        assertArrayEquals(before, after, "an interleaved request must not influence the result");
    }

    @Test
    void noSessionCookieIsEmitted() {
        assertNull(post(BODY_A).header("Set-Cookie"), "the service is cookie-less by contract");
        assertNull(post(BODY_B).header("Set-Cookie"), "the service is cookie-less by contract");
    }

    @Test
    void concurrentRequestsDoNotInterfere() throws Exception {
        int parallelism = 20;
        List<String> bodies = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < parallelism; i++) {
            String body = "{\"initialAmount\":" + (1000 + i * 100) + ".00,\"monthlyAmount\":"
                + (50 + i) + ".00,\"durationYears\":" + (1 + i % 30) + ",\"annualRate\":0.04}";
            bodies.add(body);
            expected.add(post(body).asString());
        }

        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (String body : bodies) {
                calls.add(() -> post(body).asString());
            }
            List<Future<String>> results = pool.invokeAll(calls);
            for (int i = 0; i < parallelism; i++) {
                assertEquals(expected.get(i), results.get(i).get(),
                    "concurrent request " + i + " returned another request's result");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private ExtractableResponse<Response> post(String body) {
        return given().contentType(ContentType.JSON).body(body)
            .when().post("/v1/simulations").then().statusCode(200).extract();
    }
}
