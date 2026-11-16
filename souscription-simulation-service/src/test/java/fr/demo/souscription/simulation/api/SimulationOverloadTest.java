package fr.demo.souscription.simulation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.simulation.model.Simulation;
import fr.demo.souscription.simulation.service.SimulationService;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import fr.demo.souscription.simulation.security.MutualTlsHarness;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * W4-T03 : au-delà du bulkhead (20 appels concurrents + file de 40), les requêtes
 * excédentaires doivent recevoir un 503 application/problem+json immédiat, jamais un 500,
 * jamais un 429, jamais une connexion suspendue.
 *
 * Le calcul réel coûte moins d'une milliseconde, donc 60 places ne peuvent pas être saturées
 * par du trafic honnête : le service est remplacé pour la durée du test par une implémentation
 * lente (QuarkusMock, restaurée automatiquement après le test) afin que la concurrence franchisse
 * réellement la capacité annoncée.
 */
@QuarkusTest
@TestProfile(SimulationOverloadTest.LargePoolProfile.class)
class SimulationOverloadTest {

    /**
     * Le pool de production (max-threads = 16) est plus étroit que le bulkhead (20) : la limite
     * atteinte en premier serait celle du pool, pas celle du contrat. Ce profil élargit le pool
     * pour que le rejet observé vienne bien du bulkhead annoté sur la ressource.
     */
    public static final class LargePoolProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.thread-pool.core-threads", "64",
                    "quarkus.thread-pool.max-threads", "64",
                    "quarkus.thread-pool.queue-size", "256",
                    // 150 connexions ouvertes d'un seul coup dépassent le backlog d'accept par
                    // défaut (128) : le noyau réinitialise les connexions en excès et le client
                    // voit une IOException au lieu du 503 attendu. On élargit le backlog pour que
                    // le rejet mesuré reste celui du bulkhead.
                    "quarkus.http.accept-backlog", "512",
                    "quarkus.vertx.max-event-loop-execute-time", "10S",
                    "quarkus.vertx.warning-exception-time", "10S");
        }
    }

    /** 20 (bulkhead) + 40 (file) = 60 places ; on tire largement au-delà. */
    private static final int CAPACITY = 60;
    private static final int CONCURRENT_CALLS = 150;
    /** Assez long pour saturer, assez court pour rester sous le timeout serveur de 2 500 ms. */
    private static final Duration HOLD = Duration.ofMillis(600);

    private static final String BODY =
            "{\"initialAmount\":100000.00,\"monthlyAmount\":2000.00,\"durationYears\":30,\"annualRate\":0.06}";

    @BeforeEach
    void installSlowService() {
        QuarkusMock.installMockForType(new SlowSimulationService(), SimulationService.class);
    }

    @Test
    void excessCallsAreRejectedWithProblemJsonServiceUnavailable() throws Exception {
        List<HttpResponse<String>> responses = fire();

        assertEquals(CONCURRENT_CALLS, responses.size(),
                "chaque requête soumise doit obtenir une réponse, pas une connexion suspendue");

        List<Integer> statuses = responses.stream().map(HttpResponse::statusCode).toList();
        long rejected = statuses.stream().filter(status -> status == 503).count();
        long accepted = statuses.stream().filter(status -> status == 200).count();

        assertTrue(statuses.stream().noneMatch(status -> status == 500),
                "la surcharge ne doit jamais produire un 500 : " + statuses);
        assertTrue(statuses.stream().noneMatch(status -> status == 429),
                "le 429 est réservé au rate limiting, jamais émis par le bulkhead : " + statuses);
        assertTrue(statuses.stream().allMatch(status -> status == 200 || status == 503),
                "statuts inattendus sous charge : " + statuses);
        assertTrue(rejected > 0,
                "aucune requête rejetée alors que " + CONCURRENT_CALLS + " appels dépassent les "
                        + CAPACITY + " places du bulkhead : " + statuses);
        assertEquals(CONCURRENT_CALLS, accepted + rejected,
                "chaque réponse est soit 200 soit 503 : " + statuses);
        assertTrue(accepted < CONCURRENT_CALLS,
                "la capacité doit être bornée : les " + CONCURRENT_CALLS
                        + " appels concurrents ne peuvent pas tous être servis");

        for (HttpResponse<String> response : responses) {
            if (response.statusCode() != 503) {
                continue;
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            assertTrue(contentType.startsWith("application/problem+json"),
                    "le rejet de surcharge doit être un problem+json, était [" + contentType + "]");
            String payload = response.body();
            assertTrue(payload.contains("\"status\":503"),
                    "le corps doit porter status 503 : " + payload);
            assertTrue(payload.contains("\"type\":\"about:blank\"") && payload.contains("\"title\""),
                    "le corps doit respecter la forme FieldErrors : " + payload);
            assertTrue(!payload.contains("\"errors\""),
                    "un rejet de surcharge n'est pas une erreur de champ : " + payload);
        }
    }

    private List<HttpResponse<String>> fire() throws Exception {
        // The traffic port is TLS 1.3 with client-auth required (W6-T01): the burst client
        // presents the contracted gateway identity, like every caller in production.
        HttpClient client = MutualTlsHarness.httpClient(Duration.ofSeconds(5));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MutualTlsHarness.trafficBaseUri() + "/v1/simulations"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BODY))
                .build();

        List<CompletableFuture<HttpResponse<String>>> calls = IntStream.range(0, CONCURRENT_CALLS)
                .mapToObj(i -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .toList();

        List<HttpResponse<String>> responses = new ArrayList<>(CONCURRENT_CALLS);
        for (CompletableFuture<HttpResponse<String>> call : calls) {
            responses.add(awaitWithConnectionRetry(call, client, request));
        }
        return responses;
    }

    /**
     * Une connexion réinitialisée pendant la rafale (« header parser received no bytes ») n'est
     * pas une réponse du service : c'est un échec d'établissement de connexion au niveau de l'OS,
     * pas un rejet du bulkhead. On rejoue alors l'appel pour obtenir un vrai statut HTTP, au lieu
     * de compter l'incident comme un rejet ou de faire échouer le test sur un aléa système.
     */
    private static HttpResponse<String> awaitWithConnectionRetry(
            CompletableFuture<HttpResponse<String>> call, HttpClient client, HttpRequest request)
            throws Exception {
        try {
            return call.get();
        } catch (ExecutionException failure) {
            if (!(failure.getCause() instanceof IOException)) {
                throw failure;
            }
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** Même résultat que le service réel, mais assez lente pour saturer la capacité bornée. */
    private static final class SlowSimulationService extends SimulationService {
        @Override
        public Simulation calculate(BigDecimal initial, BigDecimal monthly, int years,
                                    BigDecimal annualRate) {
            try {
                Thread.sleep(HOLD.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return super.calculate(initial, monthly, years, annualRate);
        }
    }
}
