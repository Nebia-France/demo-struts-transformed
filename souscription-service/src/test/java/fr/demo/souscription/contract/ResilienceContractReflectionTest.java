package fr.demo.souscription.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import fr.demo.souscription.integration.CatalogueClient;
import fr.demo.souscription.integration.SimulationClient;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Derive des parametres de resilience (plan 42, etape 3) : les valeurs des annotations
 * MicroProfile Fault Tolerance des deux clients REST sont lues par reflexion et comparees a
 * celles de chaque {@code resilience-contract.json} lu sur le disque. Toute divergence fait
 * echouer le build ; aucune valeur attendue n'est ecrite en dur dans ce test.
 *
 * <p>Une surcharge par configuration ({@code <classe>/<methode>/Retry/maxRetries=...}) rendrait
 * l'annotation trompeuse : son absence de {@code application.properties} est donc aussi verifiee.
 */
class ResilienceContractReflectionTest {

    private static final Path APPLICATION_PROPERTIES =
            Path.of("src", "main", "resources", "application.properties");

    private static Method operation(String client) {
        Class<?> type = "catalogue".equals(client) ? CatalogueClient.class : SimulationClient.class;
        String name = "catalogue".equals(client) ? "findProduct" : "compute";
        return Arrays.stream(type.getMethods()).filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private static JsonNode contract(String communicationId) {
        return ContractDocuments.read(
                ContractDocuments.contract(communicationId, "resilience-contract.json"));
    }

    /** "3s", "10s", "100ms" en millisecondes. */
    private static long millis(String duration) {
        Matcher matcher = Pattern.compile("(\\d+)\\s*(ms|s)").matcher(duration.trim());
        assertTrue(matcher.matches(), "duree illisible dans le contrat : " + duration);
        long value = Long.parseLong(matcher.group(1));
        return "s".equals(matcher.group(2)) ? value * 1000 : value;
    }

    private static long toMillis(long value, ChronoUnit unit) {
        return unit.getDuration().toMillis() * value;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"souscription-service-to-souscription-catalogue-service,catalogue",
                "souscription-service-to-souscription-simulation-service,simulation"})
    void theTimeoutEqualsTheContract(String communicationId, String client) {
        Timeout timeout = operation(client).getAnnotation(Timeout.class);
        assertNotNull(timeout, "@Timeout absent");
        assertEquals(millis(contract(communicationId).path("timeout").path("value").asText()),
                toMillis(timeout.value(), timeout.unit()));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"souscription-service-to-souscription-catalogue-service,catalogue",
                "souscription-service-to-souscription-simulation-service,simulation"})
    void theRetryEqualsTheContract(String communicationId, String client) {
        JsonNode retry = contract(communicationId).path("retry");
        Retry annotation = operation(client).getAnnotation(Retry.class);
        ExponentialBackoff backoff = operation(client).getAnnotation(ExponentialBackoff.class);
        assertNotNull(annotation, "@Retry absent");
        assertNotNull(backoff, "@ExponentialBackoff absent alors que le contrat l'exige");

        assertEquals(retry.path("maxRetries").asInt(), annotation.maxRetries(), "maxRetries");

        Matcher policy = Pattern.compile("exponential with full jitter, (\\S+) base, (\\S+) cap")
                .matcher(retry.path("backoff").asText());
        assertTrue(policy.matches(), "politique de backoff illisible : " + retry.path("backoff"));
        assertEquals(millis(policy.group(1)), toMillis(annotation.delay(), annotation.delayUnit()),
                "base du backoff");
        assertEquals(millis(policy.group(2)), toMillis(backoff.maxDelay(), backoff.maxDelayUnit()),
                "plafond du backoff");
        assertTrue(annotation.jitter() > 0, "le contrat exige une gigue");

        // Conditions non rejouables du contrat (4xx, 429) : les exceptions qui les portent ne
        // doivent pas figurer dans retryOn.
        List<String> retryOn = Arrays.stream(annotation.retryOn()).map(Class::getSimpleName).toList();
        assertTrue(retryOn.stream().noneMatch(name -> name.endsWith("ClientException")
                        || name.endsWith("NotFoundException") || name.endsWith("RejectedException")),
                "une condition non rejouable est reessayee : " + retryOn);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"souscription-service-to-souscription-catalogue-service,catalogue",
                "souscription-service-to-souscription-simulation-service,simulation"})
    void theCircuitBreakerEqualsTheContract(String communicationId, String client) {
        JsonNode breaker = contract(communicationId).path("circuitBreaker");
        CircuitBreaker annotation = operation(client).getAnnotation(CircuitBreaker.class);
        assertEquals(breaker.path("enabled").asBoolean(), annotation != null, "disjoncteur actif");

        assertEquals(breaker.path("requestVolumeThreshold").asInt(),
                annotation.requestVolumeThreshold(), "requestVolumeThreshold");
        assertEquals(breaker.path("failureRatio").asDouble(), annotation.failureRatio(), 0.0,
                "failureRatio");
        assertEquals(millis(breaker.path("delay").asText()),
                toMillis(annotation.delay(), annotation.delayUnit()), "delay");
        assertEquals(breaker.path("successThreshold").asInt(), annotation.successThreshold(),
                "successThreshold");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"souscription-service-to-souscription-catalogue-service,catalogue",
                "souscription-service-to-souscription-simulation-service,simulation"})
    void theBulkheadEqualsTheContract(String communicationId, String client) {
        Bulkhead annotation = operation(client).getAnnotation(Bulkhead.class);
        assertNotNull(annotation, "@Bulkhead absent");
        assertEquals(contract(communicationId).path("bulkhead").path("maxConcurrentCalls").asInt(),
                annotation.value());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"catalogue", "simulation"})
    void noConfigurationOverridesTheAnnotations(String client) throws IOException {
        Method method = operation(client);
        String prefix = method.getDeclaringClass().getName() + "/" + method.getName() + "/";
        String simplePrefix = method.getDeclaringClass().getSimpleName() + "/";
        List<String> overrides = Files.readAllLines(APPLICATION_PROPERTIES).stream()
                .map(String::trim)
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.contains(prefix) || line.contains(simplePrefix)
                        || line.matches("^(%\\w+\\.)?(MP_Fault_Tolerance|smallrye\\.faulttolerance"
                                + "|quarkus\\.fault-tolerance)\\..*"))
                .toList();
        assertEquals(List.of(), overrides,
                "une surcharge de configuration masquerait les valeurs du contrat");
    }
}
