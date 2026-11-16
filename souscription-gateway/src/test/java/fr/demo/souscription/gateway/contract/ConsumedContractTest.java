package fr.demo.souscription.gateway.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import fr.demo.souscription.gateway.api.dto.ConfirmationRequest;
import fr.demo.souscription.gateway.api.dto.DraftStartRequest;
import fr.demo.souscription.gateway.api.dto.SimulationInput;
import fr.demo.souscription.gateway.client.CatalogueClient;
import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import fr.demo.souscription.gateway.client.SimulationClient;
import fr.demo.souscription.gateway.client.SimulationComputeRequest;
import fr.demo.souscription.gateway.client.SubscriptionClient;
import fr.demo.souscription.gateway.contract.ContractDocuments.Located;
import fr.demo.souscription.gateway.contract.ContractDocuments.Operation;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Consumer side of the three downstream contracts (W7-T03), one parameterised case per
 * downstream: {@code souscription-gateway-to-souscription-catalogue-service},
 * {@code -simulation-service} and {@code -service}.
 *
 * <p>In the source these edges were in-process Java calls checked by the compiler. They are now
 * HTTP calls checked by nothing unless this test runs, so each case asserts, against the committed
 * {@code openapi.yaml} of the downstream:
 *
 * <ul>
 *   <li>the {@code @RegisterRestClient} interface: every method maps to a declared operation
 *       (method, base path + path template), the path parameters are the declared ones, every
 *       required header is sent and no undeclared header is;</li>
 *   <li>the payload types: request and response records have exactly the members of the contract
 *       schema (all required ones included) with a compatible JSON type, recursively;</li>
 *   <li>the WireMock doubles used by the whole suite are byte-identical to what
 *       {@code src/test/scripts/generate-stubs.sh} derives from the contracts (plan 29): a
 *       hand-edited or stale stub fails here;</li>
 *   <li>a real call of the client is answered by a contract-derived stub, and the request body the
 *       gateway put on the wire validates against the contract request schema.</li>
 * </ul>
 *
 * <p>A breaking downstream change (a renamed or added required member, a moved path, a removed
 * operation) therefore fails the gateway build; {@code src/test/scripts/contract-breaking-check.sh}
 * demonstrates it.
 */
@QuarkusTest
class ConsumedContractTest {

    private static final Map<String, String> CONTRACTS = Map.of(
            "catalogue", "souscription-gateway-to-souscription-catalogue-service",
            "simulation", "souscription-gateway-to-souscription-simulation-service",
            "subscription", "souscription-gateway-to-souscription-service");

    private static final Map<String, Class<?>> CLIENTS = Map.of(
            "catalogue", CatalogueClient.class,
            "simulation", SimulationClient.class,
            "subscription", SubscriptionClient.class);

    private static final java.nio.file.Path GENERATOR =
            java.nio.file.Path.of("src", "test", "scripts", "generate-stubs.sh");

    private static final java.nio.file.Path COMMITTED_STUBS =
            java.nio.file.Path.of("src", "test", "resources", "wiremock");

    private static final java.nio.file.Path GENERATED_STUBS =
            java.nio.file.Path.of("target", "contract-stubs");

    private static boolean generated;

    @RestClient
    CatalogueClient catalogue;

    @RestClient
    SimulationClient simulation;

    @RestClient
    SubscriptionClient subscriptions;

    @AfterEach
    void resetTheConfirmationScenario() {
        DownstreamWireMocks.server("subscription").resetScenarios();
    }

    // ------------------------------------------------------------------ interface vs operations

    @ParameterizedTest
    @ValueSource(strings = {"catalogue", "simulation", "subscription"})
    void theClientInterfaceMatchesTheDeclaredOperations(String configKey) {
        Class<?> client = CLIENTS.get(configKey);
        assertEquals(configKey, client.getAnnotation(RegisterRestClient.class).configKey());
        java.nio.file.Path contract = ContractDocuments.contractFile(CONTRACTS.get(configKey));
        String base = ContractDocuments.basePath(ContractDocuments.load(contract));
        Map<String, Operation> declared = new TreeMap<>();
        ContractDocuments.operations(contract).values()
                .forEach(op -> declared.put(op.method().toUpperCase() + " " + base + op.path(), op));

        List<String> divergences = new ArrayList<>();
        TreeSet<String> called = new TreeSet<>();
        for (Method method : sortedMethods(client)) {
            String key = verbOf(method) + " " + pathOf(client, method);
            called.add(key);
            Operation operation = declared.get(key);
            if (operation == null) {
                divergences.add(method.getName() + ": " + key + " is not declared by " + contract);
                continue;
            }
            TreeSet<String> declaredPath = parameterNames(operation, "path", false);
            TreeSet<String> sentPath = annotatedNames(method, PathParam.class);
            if (!declaredPath.equals(sentPath)) {
                divergences.add(method.getName() + ": path parameters " + sentPath + ", contract "
                        + declaredPath);
            }
            TreeSet<String> sentHeaders = annotatedNames(method, HeaderParam.class);
            for (String required : parameterNames(operation, "header", true)) {
                if (sentHeaders.stream().noneMatch(required::equalsIgnoreCase)) {
                    divergences.add(method.getName() + ": required header " + required
                            + " is not sent");
                }
            }
            TreeSet<String> declaredHeaders = parameterNames(operation, "header", false);
            for (String header : sentHeaders) {
                if (declaredHeaders.stream().noneMatch(header::equalsIgnoreCase)) {
                    divergences.add(method.getName() + ": header " + header
                            + " is not declared by the contract");
                }
            }
        }
        assertEquals(List.of(), divergences, configKey + " client diverges from its contract");
        assertEquals(declared.keySet(), called,
                configKey + ": the client must cover exactly the operations of the contract");
    }

    // ---------------------------------------------------------------- payload types vs schemas

    @ParameterizedTest
    @ValueSource(strings = {"catalogue", "simulation", "subscription"})
    void thePayloadTypesMatchTheContractSchemas(String configKey) {
        Class<?> client = CLIENTS.get(configKey);
        java.nio.file.Path contract = ContractDocuments.contractFile(CONTRACTS.get(configKey));
        String base = ContractDocuments.basePath(ContractDocuments.load(contract));
        Map<String, Operation> declared = new TreeMap<>();
        ContractDocuments.operations(contract).values()
                .forEach(op -> declared.put(op.method().toUpperCase() + " " + base + op.path(), op));

        List<String> divergences = new ArrayList<>();
        for (Method method : sortedMethods(client)) {
            Operation operation = declared.get(verbOf(method) + " " + pathOf(client, method));
            assertNotNull(operation, method + " has no contract operation");
            String where = method.getName();

            Parameter body = bodyParameter(method);
            JsonNode requestBody = operation.node().get("requestBody");
            if (body == null && requestBody != null) {
                divergences.add(where + ": the contract declares a request body, none is sent");
            } else if (body != null && requestBody == null) {
                divergences.add(where + ": a body is sent, the contract declares none");
            } else if (body != null) {
                Located schema = ContractDocuments.contentSchema(contract, requestBody);
                compare(body.getParameterizedType(), schema, where + " request", divergences);
            }

            Located success = successSchema(contract, operation);
            Type returned = unwrap(method.getGenericReturnType());
            if (success == null) {
                divergences.add(where + ": no 2xx JSON response declared");
            } else {
                compare(returned, success, where + " response", divergences);
            }
        }
        assertEquals(List.of(), divergences, configKey + " payload types diverge from the contract");
    }

    // ------------------------------------------------------------------ stubs are the generated

    @ParameterizedTest
    @ValueSource(strings = {"catalogue", "simulation", "subscription"})
    void theWireMockStubsAreByteIdenticalToTheGeneratedOnes(String configKey) throws Exception {
        generateStubsOnce();
        Map<String, byte[]> committed = files(COMMITTED_STUBS.resolve(configKey));
        Map<String, byte[]> regenerated = files(GENERATED_STUBS.resolve(configKey));
        assertFalse(committed.isEmpty(), "no committed stub for " + configKey);
        assertEquals(regenerated.keySet(), committed.keySet(),
                configKey + ": the committed stub files are not the generated set");
        for (Map.Entry<String, byte[]> file : committed.entrySet()) {
            assertTrue(java.util.Arrays.equals(regenerated.get(file.getKey()), file.getValue()),
                    configKey + "/" + file.getKey() + " differs from the generate-stubs.sh output:"
                            + " the stub was edited by hand or the contract changed; run "
                            + GENERATOR);
        }
        for (byte[] content : committed.values()) {
            JsonNode mappings = ContractDocuments.parseJson(
                    new String(content, StandardCharsets.UTF_8)).get("mappings");
            mappings.forEach(mapping -> assertEquals(CONTRACTS.get(configKey),
                    mapping.at("/metadata/contract").asText(),
                    "stub derived from another contract: " + mapping.get("name")));
        }
    }

    // ------------------------------------------------------------- real call vs contract stubs

    @ParameterizedTest
    @ValueSource(strings = {"catalogue", "simulation", "subscription"})
    void aRealCallIsAnsweredByAContractStubWithAConformingRequest(String configKey) {
        java.nio.file.Path contract = ContractDocuments.contractFile(CONTRACTS.get(configKey));
        String base = ContractDocuments.basePath(ContractDocuments.load(contract));
        Duration wait = Duration.ofSeconds(5);
        List<String> called = new ArrayList<>();
        switch (configKey) {
            case "catalogue" -> {
                assertFalse(catalogue.list().await().atMost(wait).isEmpty());
                called.add("GET /products");
                assertEquals("PRUDENT", catalogue.detail("PRUDENT").await().atMost(wait).code());
                called.add("GET /products/{code}");
            }
            case "simulation" -> {
                simulation.compute(SimulationComputeRequest.of(
                        new SimulationInput(new BigDecimal("1000.00"), new BigDecimal("100.00"), 10),
                        new BigDecimal("0.020000"))).await().atMost(wait);
                called.add("POST /simulations");
            }
            case "subscription" -> {
                String draft = subscriptions.startDraft(new DraftStartRequest("PRUDENT"))
                        .await().atMost(wait).draftId();
                called.add("POST /drafts");
                String key = "22222222-2222-2222-2222-222222222222";
                RestResponse<?> confirmed = subscriptions.confirm(UUID.fromString(draft), key,
                        new ConfirmationRequest(key)).await().atMost(wait);
                assertTrue(confirmed.getStatus() == 201 || confirmed.getStatus() == 200,
                        "confirmation status " + confirmed.getStatus());
                called.add("POST /drafts/{draftId}/confirmation");
            }
            default -> throw new IllegalArgumentException(configKey);
        }

        Map<String, Operation> operations = ContractDocuments.operations(contract);
        List<ServeEvent> events = DownstreamWireMocks.server(configKey).getAllServeEvents();
        List<String> violations = new ArrayList<>();
        for (String operationKey : called) {
            Operation operation = operations.get(operationKey);
            String regex = (base + operation.path()).replaceAll("\\{[^}]+}", "[^/]+");
            ServeEvent event = events.stream()
                    .filter(e -> e.getRequest().getMethod().getName()
                            .equalsIgnoreCase(operation.method()))
                    .filter(e -> e.getRequest().getUrl().split("\\?")[0].matches(regex))
                    .findFirst().orElse(null);
            if (event == null) {
                violations.add(operationKey + ": no request reached the double");
                continue;
            }
            if (!event.getWasMatched() || event.getStubMapping().getMetadata() == null
                    || !CONTRACTS.get(configKey).equals(
                            event.getStubMapping().getMetadata().getString("contract"))) {
                violations.add(operationKey + ": not answered by a contract-derived stub");
            }
            if (event.getResponse().getStatus() >= 400) {
                violations.add(operationKey + ": the contract stub rejected the request with "
                        + event.getResponse().getStatus());
            }
            JsonNode requestBody = operation.node().get("requestBody");
            if (requestBody != null) {
                Located schema = ContractDocuments.contentSchema(contract, requestBody);
                violations.addAll(ContractDocuments.validate(schema.file(), schema.node(),
                        ContractDocuments.parseJson(event.getRequest().getBodyAsString()),
                        operationKey + " request"));
            }
        }
        assertEquals(List.of(), violations,
                configKey + ": the requests on the wire diverge from the contract");
    }

    // ---------------------------------------------------------------------------------- helpers

    private static List<Method> sortedMethods(Class<?> client) {
        return Stream.of(client.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !m.isDefault())
                .sorted(Comparator.comparing(Method::getName)).toList();
    }

    private static String verbOf(Method method) {
        if (method.isAnnotationPresent(GET.class)) {
            return "GET";
        }
        if (method.isAnnotationPresent(POST.class)) {
            return "POST";
        }
        if (method.isAnnotationPresent(PUT.class)) {
            return "PUT";
        }
        throw new AssertionError("no HTTP verb on " + method);
    }

    private static String pathOf(Class<?> client, Method method) {
        String sub = method.isAnnotationPresent(Path.class) ? method.getAnnotation(Path.class).value()
                : "";
        return (client.getAnnotation(Path.class).value() + sub).replaceAll("/$", "");
    }

    private static TreeSet<String> parameterNames(Operation operation, String location,
            boolean requiredOnly) {
        TreeSet<String> names = new TreeSet<>();
        operation.parameters().stream()
                .filter(p -> location.equals(p.path("in").asText()))
                .filter(p -> !requiredOnly || p.path("required").asBoolean(false))
                .forEach(p -> names.add(p.get("name").asText()));
        return names;
    }

    private static TreeSet<String> annotatedNames(Method method,
            Class<? extends Annotation> annotation) {
        TreeSet<String> names = new TreeSet<>();
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(PathParam.class) && annotation == PathParam.class) {
                names.add(parameter.getAnnotation(PathParam.class).value());
            }
            if (parameter.isAnnotationPresent(HeaderParam.class) && annotation == HeaderParam.class) {
                names.add(parameter.getAnnotation(HeaderParam.class).value());
            }
        }
        return names;
    }

    private static Parameter bodyParameter(Method method) {
        for (Parameter parameter : method.getParameters()) {
            if (parameter.getAnnotations().length == 0) {
                return parameter;
            }
        }
        return null;
    }

    /** Schema of the lowest declared 2xx response, or {@code null}. */
    private static Located successSchema(java.nio.file.Path contract, Operation operation) {
        TreeSet<String> codes = new TreeSet<>();
        operation.node().get("responses").fieldNames().forEachRemaining(code -> {
            if (code.startsWith("2")) {
                codes.add(code);
            }
        });
        return codes.isEmpty() ? null : ContractDocuments.contentSchema(contract,
                operation.node().get("responses").get(codes.first()));
    }

    /** {@code Uni<T>} and {@code RestResponse<T>} are transport envelopes, not payloads. */
    private static Type unwrap(Type type) {
        Type current = type;
        while (current instanceof ParameterizedType parameterized
                && (parameterized.getRawType() == Uni.class
                        || parameterized.getRawType() == RestResponse.class)) {
            current = parameterized.getActualTypeArguments()[0];
        }
        return current;
    }

    /** Structural comparison of a Java payload type with a contract schema. */
    private static void compare(Type type, Located located, String where, List<String> divergences) {
        Located schema = ContractDocuments.deref(located.file(), located.node());
        JsonNode node = schema.node();
        if (node.has("oneOf")) {
            List<JsonNode> concrete = new ArrayList<>();
            for (JsonNode option : node.get("oneOf")) {
                if (!"null".equals(ContractDocuments.deref(schema.file(), option).node()
                        .path("type").asText())) {
                    concrete.add(option);
                }
            }
            if (concrete.size() != 1) {
                divergences.add(where + ": oneOf with " + concrete.size() + " non-null branches");
                return;
            }
            compare(type, new Located(schema.file(), concrete.get(0)), where, divergences);
            return;
        }
        String expected = node.path("type").asText();
        Class<?> raw = type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) type;
        if (Collection.class.isAssignableFrom(raw)) {
            if (!"array".equals(expected)) {
                divergences.add(where + ": a collection where the contract declares " + expected);
                return;
            }
            Type item = ((ParameterizedType) type).getActualTypeArguments()[0];
            compare(item, new Located(schema.file(), node.get("items")), where + "[]", divergences);
            return;
        }
        if (raw.isRecord()) {
            if (!"object".equals(expected)) {
                divergences.add(where + ": record " + raw.getSimpleName() + " where the contract"
                        + " declares " + expected);
                return;
            }
            Map<String, RecordComponent> members = new TreeMap<>();
            for (RecordComponent component : raw.getRecordComponents()) {
                JsonProperty renamed = component.getAccessor().getAnnotation(JsonProperty.class);
                members.put(renamed != null && !renamed.value().isEmpty() ? renamed.value()
                        : component.getName(), component);
            }
            TreeSet<String> declared = new TreeSet<>();
            node.path("properties").fieldNames().forEachRemaining(declared::add);
            if (!declared.equals(members.keySet())) {
                divergences.add(where + ": " + raw.getSimpleName() + " members " + members.keySet()
                        + ", contract " + declared);
            }
            for (JsonNode required : node.path("required")) {
                if (!members.containsKey(required.asText())) {
                    divergences.add(where + ": required member '" + required.asText()
                            + "' has no counterpart in " + raw.getSimpleName());
                }
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = node.path("properties").fields();
                    it.hasNext(); ) {
                Map.Entry<String, JsonNode> property = it.next();
                RecordComponent component = members.get(property.getKey());
                if (component != null) {
                    compare(component.getGenericType(),
                            new Located(schema.file(), property.getValue()),
                            where + "." + property.getKey(), divergences);
                }
            }
            return;
        }
        String actual = jsonTypeOf(raw);
        if (actual == null) {
            divergences.add(where + ": unsupported payload type " + raw.getName());
        } else if (!expected.isEmpty() && !expected.equals(actual)) {
            divergences.add(where + ": " + raw.getSimpleName() + " serialises as " + actual
                    + ", contract declares " + expected);
        }
    }

    private static String jsonTypeOf(Class<?> raw) {
        if (raw == String.class || raw == BigDecimal.class || raw == LocalDate.class
                || raw == Instant.class || raw == UUID.class) {
            // BigDecimal: every amount and rate component carries a ScaledDecimalSerializer
            return "string";
        }
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class) {
            return "integer";
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return "boolean";
        }
        return null;
    }

    private static synchronized void generateStubsOnce() throws Exception {
        if (generated) {
            return;
        }
        deleteTree(GENERATED_STUBS);
        ProcessBuilder builder = new ProcessBuilder("bash", GENERATOR.toString())
                .redirectErrorStream(true);
        builder.environment().put("OUTPUT_DIR", GENERATED_STUBS.toAbsolutePath().toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "generate-stubs.sh did not finish");
        assertEquals(0, process.exitValue(), "generate-stubs.sh failed (python3 + PyYAML are"
                + " required by the contract stage): " + output);
        generated = true;
    }

    private static Map<String, byte[]> files(java.nio.file.Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        try (Stream<java.nio.file.Path> walk = Files.walk(root)) {
            for (java.nio.file.Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(file).toString(), Files.readAllBytes(file));
            }
        }
        return files;
    }

    /** Removes a previous generator output, file by file (never a recursive delete call). */
    private static void deleteTree(java.nio.file.Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<java.nio.file.Path> walk = Files.walk(root)) {
            for (java.nio.file.Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
