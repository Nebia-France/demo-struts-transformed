package fr.demo.souscription.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import fr.demo.souscription.gateway.api.dto.DraftStartRequest;
import fr.demo.souscription.gateway.api.dto.ProductSummary;
import fr.demo.souscription.gateway.api.dto.SimulationInput;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

/**
 * Parity check for 17-souscription-gateway-rest-clients.
 *
 * <p>Three in-process calls of the source application ({@code CatalogService},
 * {@code SimulationService}, {@code SubscriptionService}, all fetched from a
 * {@code ServletContext} attribute) became three contract-bound remote edges. The journey outcome
 * must be unchanged; only the transport changes.
 *
 * <p>The reference is authoritative and is never the client: each operation declared by a
 * {@code @RegisterRestClient} interface is matched against the {@code openapi.yaml} of the consumed
 * contract under {@code global/contracts/}. An invented path otherwise fails only against the real
 * provider, long after this plan. The test also pins the three risks of the migration: a blocking
 * return type cancelling the reactive model, a hardcoded URI or credential, and a provisional stub
 * surviving into {@code src/main/java}.
 */
@QuarkusTest
class DownstreamClientParityTest {

    private static final java.nio.file.Path CONTRACTS =
            java.nio.file.Path.of("..", "global", "contracts");

    private static final java.nio.file.Path MAIN_SOURCES =
            java.nio.file.Path.of("src", "main", "java");

    /** Consumed contract directory per REST client, keyed by {@code configKey}. */
    private static final Map<String, Class<?>> CLIENTS = Map.of(
            "catalogue", CatalogueClient.class,
            "simulation", SimulationClient.class,
            "subscription", SubscriptionClient.class);

    private static final Map<String, String> CONTRACT_DIRS = Map.of(
            "catalogue", "souscription-gateway-to-souscription-catalogue-service",
            "simulation", "souscription-gateway-to-souscription-simulation-service",
            "subscription", "souscription-gateway-to-souscription-service");

    @RestClient
    CatalogueClient catalogue;

    @RestClient
    SimulationClient simulation;

    @RestClient
    SubscriptionClient subscriptions;

    // ---------------------------------------------------------------- step 1: contract paths

    @Test
    void everyClientOperationIsDeclaredByTheConsumedContract() throws Exception {
        for (Map.Entry<String, Class<?>> entry : CLIENTS.entrySet()) {
            String configKey = entry.getKey();
            assertEquals(contractOperations(configKey), clientOperations(entry.getValue()),
                    "client " + entry.getValue().getSimpleName()
                            + " diverges from the consumed contract of " + configKey
                            + " (the provider owns its /v1 surface: fix the client, not the contract)");
        }
    }

    /** {@code METHOD <server base path><contract path>} of every operation of a contract. */
    private static TreeSet<String> contractOperations(String configKey) throws Exception {
        JsonNode document = new ObjectMapper(new YAMLFactory())
                .readTree(Files.readString(
                        CONTRACTS.resolve(CONTRACT_DIRS.get(configKey)).resolve("openapi.yaml")));

        // The client base URI is the host only, so the /v1 prefix of the server URL belongs to the
        // path declared on the interface.
        String basePath = URI.create(document.get("servers").get(0).get("url").asText()).getPath();
        assertFalse(basePath.isBlank(), "contract " + configKey + " declares no server base path");

        TreeSet<String> operations = new TreeSet<>();
        JsonNode paths = document.get("paths");
        for (Iterator<String> it = paths.fieldNames(); it.hasNext(); ) {
            String path = it.next();
            for (Iterator<String> verbs = paths.get(path).fieldNames(); verbs.hasNext(); ) {
                String verb = verbs.next();
                if (List.of("get", "post", "put", "delete", "patch", "head").contains(verb)) {
                    operations.add(verb.toUpperCase() + " " + basePath + path);
                }
            }
        }
        return operations;
    }

    /** {@code METHOD <path>} of every method declared by a REST client interface. */
    private static TreeSet<String> clientOperations(Class<?> client) {
        String root = client.getAnnotation(Path.class).value();
        TreeSet<String> operations = new TreeSet<>();
        for (Method method : client.getDeclaredMethods()) {
            String sub = method.isAnnotationPresent(Path.class)
                    ? method.getAnnotation(Path.class).value() : "";
            operations.add(verbOf(method) + " " + (root + sub).replaceAll("/$", ""));
        }
        return operations;
    }

    private static String verbOf(Method method) {
        if (method.isAnnotationPresent(GET.class)) {
            return HttpMethod.GET;
        }
        if (method.isAnnotationPresent(POST.class)) {
            return HttpMethod.POST;
        }
        if (method.isAnnotationPresent(PUT.class)) {
            return HttpMethod.PUT;
        }
        throw new AssertionError("no HTTP verb on " + method);
    }

    // ---------------------------------------------------------------- step 2: exactly three edges

    @Test
    void theGatewayConsumesExactlyThreeContracts() {
        assertEquals(List.of("catalogue", "simulation", "subscription"),
                DownstreamWireMocks.CONFIG_KEYS,
                "the gateway consumes exactly three contracts; a fourth client is a topology change");
        CLIENTS.forEach((configKey, client) -> {
            assertEquals(configKey, client.getAnnotation(
                            org.eclipse.microprofile.rest.client.inject.RegisterRestClient.class)
                    .configKey(), "configKey of " + client.getSimpleName()
                    + " must match the quarkus.rest-client.<key>.* properties");
            assertNotNull(ConfigProvider.getConfig()
                            .getValue("quarkus.rest-client." + configKey + ".url", String.class),
                    "missing base URI property for " + configKey);
        });
    }

    // ---------------------------------------------------------------- step 3: reactive returns

    @Test
    void everyClientMethodReturnsUni() {
        for (Class<?> client : CLIENTS.values()) {
            for (Method method : client.getDeclaredMethods()) {
                assertEquals(Uni.class, method.getReturnType(),
                        method + " must return Uni<...>: a blocking return on the event loop"
                                + " exhausts it under load");
            }
        }
    }

    // ------------------------------------------------- step 4: no hardcoded URI, timeouts present

    @Test
    void noUriIsHardcodedAndEveryTimeoutIsConfigured() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (var files = Files.walk(MAIN_SOURCES)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String body = Files.readString(file);
                if (body.contains("\"http://") || body.contains("\"https://")) {
                    offenders.add(file.toString());
                }
            }
        }
        assertEquals(List.of(), offenders,
                "a hardcoded host cannot be switched between environments");

        assertEquals(500, timeout("catalogue", "connect-timeout"));
        assertEquals(3000, timeout("catalogue", "read-timeout"));
        assertEquals(500, timeout("simulation", "connect-timeout"));
        assertEquals(3000, timeout("simulation", "read-timeout"));
        assertEquals(500, timeout("subscription", "connect-timeout"));
        assertEquals(5000, timeout("subscription", "read-timeout"));
    }

    private static long timeout(String configKey, String property) {
        return ConfigProvider.getConfig()
                .getValue("quarkus.rest-client." + configKey + "." + property, Long.class);
    }

    // ---------------------------------------------------------------- step 5: no surviving stub

    @Test
    void noProvisionalStubSurvivesInTheMainSourceSet() throws Exception {
        assertFalse(Files.isDirectory(MAIN_SOURCES.resolve(
                        "fr/demo/souscription/gateway/client/stub")),
                "an @ApplicationScoped stub beats the REST client in CDI resolution"
                        + " and silently serves fixed data");
        try (var files = Files.walk(MAIN_SOURCES)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertFalse(Files.readString(file).contains("SWITCH-TO-REAL-CLIENT"),
                        "provisional stub marker left in " + file);
            }
        }
    }

    // --------------------------------------- steps 1/6: journal of the exact outgoing request

    @Test
    void theCatalogueCallLeavesTheContractRequestOnTheWire() {
        List<ProductSummary> products = catalogue.list().await().atMost(Duration.ofSeconds(5));

        assertEquals(List.of("PRUDENT", "EQUILIBRE", "DYNAMIQUE"),
                products.stream().map(ProductSummary::code).toList());
        DownstreamWireMocks.server("catalogue")
                .verify(getRequestedFor(urlPathEqualTo("/v1/products")));
    }

    @Test
    void theSimulationCallCarriesTheAuthoritativeRateOnTheWire() {
        var result = simulation.compute(SimulationComputeRequest.of(
                        new SimulationInput(new BigDecimal("1000.00"), new BigDecimal("100.00"), 10),
                        new BigDecimal("0.020000")))
                .await().atMost(Duration.ofSeconds(5));

        assertEquals(0, new BigDecimal("14963.21").compareTo(result.finalCapital()));
        DownstreamWireMocks.server("simulation")
                .verify(postRequestedFor(urlPathEqualTo("/v1/simulations")));
    }

    @Test
    void theDraftCallLeavesTheContractRequestOnTheWire() {
        var draft = subscriptions.startDraft(new DraftStartRequest("PRUDENT"))
                .await().atMost(Duration.ofSeconds(5));

        assertEquals("STARTED", draft.status());
        DownstreamWireMocks.server("subscription")
                .verify(postRequestedFor(urlPathEqualTo("/v1/drafts")));
    }

    // ---------------------------------------------------------------- step 6: stubs from contracts

    @Test
    void everyDownstreamHasContractDerivedWireMockMappings() throws Exception {
        for (String configKey : DownstreamWireMocks.CONFIG_KEYS) {
            java.nio.file.Path mappings = java.nio.file.Path.of(
                    "src", "test", "resources", "wiremock", configKey, "mappings");
            assertTrue(Files.isDirectory(mappings), "missing WireMock mappings for " + configKey);
            try (var files = Files.list(mappings)) {
                List<java.nio.file.Path> jsons =
                        files.filter(p -> p.toString().endsWith(".json")).toList();
                assertFalse(jsons.isEmpty(), "no stub mapping for " + configKey);
                for (java.nio.file.Path json : jsons) {
                    assertTrue(Files.readString(json).contains("contract:"),
                            json + " must name the contract it was derived from; a stub written"
                                    + " from the gateway's own assumptions passes here and fails"
                                    + " against the real provider");
                }
            }
        }
    }
}
