package fr.demo.souscription.gateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract-conformance check for 09-souscription-gateway-openapi-contract (W1-T03).
 *
 * <p>Fetches the document published on {@code /q/openapi} and asserts that every path, HTTP method
 * and {@code operationId} of the committed provider contract
 * {@code global/contracts/souscription-web-to-souscription-gateway/openapi.yaml} is present in it.
 * The committed file is the source of truth: a divergence is a contract break and must be fixed in
 * the resource annotations, never by relaxing this test.
 *
 * <p>The contract paths are relative to the {@code .../api/v1} server URL, so each one is prefixed
 * with {@code /api/v1} before being looked up in the generated document.
 *
 * <p>{@code GET /csrf-token} ({@code issueCsrfToken}) is part of the asserted surface since
 * plan 15: the whole contract is now served, with no exclusion.
 */
@QuarkusTest
class OpenApiContractTest {

    private static final Path CONTRACT =
            Path.of("..", "global", "contracts", "souscription-web-to-souscription-gateway",
                    "openapi.yaml");

    private static final String PUBLIC_PREFIX = "/api/v1";

    private static final String CSRF_OPERATION = "issueCsrfToken";

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    private JsonNode generatedDocument() {
        String body = given().port(managementPort()).accept("application/json")
                .when().get("/q/openapi")
                .then().statusCode(200)
                .extract().body().asString();
        try {
            return new ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new AssertionError("The document served by /q/openapi is not parseable JSON", e);
        }
    }

    /**
     * {@code /q/openapi} is served on the management interface
     * ({@code quarkus.smallrye-openapi.management.enabled=true}), so the document is reachable on
     * the management test port, not on the application port.
     */
    private int managementPort() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class)
                .orElse(9001);
    }

    private JsonNode contractDocument() {
        try {
            assertTrue(Files.exists(CONTRACT),
                    "Committed provider contract not found at " + CONTRACT.toAbsolutePath());
            return new ObjectMapper(new YAMLFactory()).readTree(Files.readString(CONTRACT));
        } catch (Exception e) {
            throw new AssertionError("The committed contract is not parseable YAML", e);
        }
    }

    /** Every (path, method, operationId) triple declared by the contract, prefix applied. */
    private List<String[]> expectedOperations() {
        List<String[]> expected = new ArrayList<>();
        JsonNode paths = contractDocument().get("paths");
        for (Iterator<Map.Entry<String, JsonNode>> it = paths.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> path = it.next();
            for (Iterator<Map.Entry<String, JsonNode>> m = path.getValue().fields(); m.hasNext(); ) {
                Map.Entry<String, JsonNode> method = m.next();
                if (!HTTP_METHODS.contains(method.getKey())) {
                    continue; // shared "parameters" block, not an operation
                }
                JsonNode operationId = method.getValue().get("operationId");
                assertTrue(operationId != null, "Contract operation without operationId: "
                        + method.getKey() + " " + path.getKey());
                expected.add(new String[] {
                        PUBLIC_PREFIX + path.getKey(), method.getKey(), operationId.asText() });
            }
        }
        return expected;
    }

    @Test
    void openApiDocumentIsPublishedAndCoversTheWholeContract() {
        JsonNode generated = generatedDocument();
        JsonNode generatedPaths = generated.get("paths");
        assertTrue(generatedPaths != null && generatedPaths.size() > 0,
                "/q/openapi published no paths");

        List<String> missing = new ArrayList<>();
        int asserted = 0;
        for (String[] operation : expectedOperations()) {
            String path = operation[0];
            String method = operation[1];
            String operationId = operation[2];
            asserted++;
            JsonNode item = generatedPaths.get(path);
            if (item == null) {
                missing.add(path + " (path absent)");
                continue;
            }
            JsonNode generatedOperation = item.get(method);
            if (generatedOperation == null) {
                missing.add(method.toUpperCase() + " " + path + " (method absent)");
                continue;
            }
            JsonNode generatedId = generatedOperation.get("operationId");
            if (generatedId == null || !operationId.equals(generatedId.asText())) {
                missing.add(method.toUpperCase() + " " + path + " (operationId expected "
                        + operationId + ", found " + generatedId + ")");
            }
        }

        assertEquals(List.of(), missing,
                "The published document diverges from the committed provider contract");
        assertEquals(10, asserted,
                "The contract is expected to declare 10 operations, including " + CSRF_OPERATION);
    }

    @Test
    void csrfOperationIsServed() {
        JsonNode paths = generatedDocument().get("paths");
        assertTrue(paths.has(PUBLIC_PREFIX + "/csrf-token"),
                "GET /api/v1/csrf-token must be published (W3-T02)");
        assertEquals(CSRF_OPERATION,
                paths.get(PUBLIC_PREFIX + "/csrf-token").get("get").get("operationId").asText());
    }

    @Test
    void everyPublishedOperationDeclaresAtLeastOneErrorResponse() {
        JsonNode paths = generatedDocument().get("paths");
        for (Iterator<Map.Entry<String, JsonNode>> it = paths.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> path = it.next();
            if (!path.getKey().startsWith(PUBLIC_PREFIX)) {
                continue;
            }
            for (Iterator<Map.Entry<String, JsonNode>> m = path.getValue().fields(); m.hasNext(); ) {
                Map.Entry<String, JsonNode> method = m.next();
                if (!HTTP_METHODS.contains(method.getKey())) {
                    continue;
                }
                JsonNode responses = method.getValue().get("responses");
                assertTrue(responses != null, "No responses on " + method.getKey() + " "
                        + path.getKey());
                boolean hasError = false;
                for (Iterator<String> codes = responses.fieldNames(); codes.hasNext(); ) {
                    String code = codes.next();
                    if (code.startsWith("4") || code.startsWith("5")) {
                        hasError = true;
                    }
                }
                assertTrue(hasError, "No 4xx/5xx response declared on "
                        + method.getKey().toUpperCase() + " " + path.getKey());
            }
        }
    }

    /**
     * W4-T05: every operation backed by a downstream call must declare the three statuses the
     * shared downstream mapper can produce — 502 (downstream error), 503 (downstream unavailable or
     * open circuit) and 504 (downstream timeout). {@code GET /csrf-token} is the only operation
     * served entirely at the edge, so it is excluded.
     */
    @Test
    void everyDownstreamBackedOperationDeclares502And503And504() {
        JsonNode paths = generatedDocument().get("paths");
        List<String> missing = new ArrayList<>();
        int asserted = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = paths.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> path = it.next();
            if (!path.getKey().startsWith(PUBLIC_PREFIX)
                    || path.getKey().equals(PUBLIC_PREFIX + "/csrf-token")) {
                continue;
            }
            for (Iterator<Map.Entry<String, JsonNode>> m = path.getValue().fields(); m.hasNext(); ) {
                Map.Entry<String, JsonNode> method = m.next();
                if (!HTTP_METHODS.contains(method.getKey())) {
                    continue;
                }
                asserted++;
                JsonNode responses = method.getValue().get("responses");
                for (String code : List.of("502", "503", "504")) {
                    JsonNode declared = responses == null ? null : responses.get(code);
                    if (declared == null) {
                        missing.add(method.getKey().toUpperCase() + " " + path.getKey() + " -> "
                                + code);
                    } else if (declared.at("/content/application~1problem+json/schema")
                            .isMissingNode()) {
                        missing.add(method.getKey().toUpperCase() + " " + path.getKey() + " -> "
                                + code + " (schema Problem absent)");
                    }
                }
            }
        }
        assertEquals(List.of(), missing,
                "Downstream-backed operations must declare 502, 503 and 504 with the Problem schema");
        assertTrue(asserted >= 9, "9 downstream-backed operations expected, found " + asserted);
    }

    @Test
    void csrfSecuritySchemeIsDeclared() {
        JsonNode schemes = generatedDocument().at("/components/securitySchemes/csrfToken");
        assertFalse(schemes.isMissingNode(), "The csrfToken security scheme is not published");
        assertEquals("X-CSRF-Token", schemes.get("name").asText());
    }

    /**
     * W3-T03: every state-changing operation of the public API must require the
     * {@code X-CSRF-Token} header through the {@code csrfToken} scheme and declare the 403 the
     * edge filter produces. Safe operations must not require it.
     */
    @Test
    void everyStateChangingOperationRequiresTheCsrfHeaderAndDeclares403() {
        Set<String> stateChanging = Set.of("post", "put", "patch", "delete");
        JsonNode paths = generatedDocument().get("paths");
        int asserted = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = paths.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> path = it.next();
            if (!path.getKey().startsWith(PUBLIC_PREFIX)) {
                continue;
            }
            for (Iterator<Map.Entry<String, JsonNode>> m = path.getValue().fields(); m.hasNext(); ) {
                Map.Entry<String, JsonNode> method = m.next();
                if (!stateChanging.contains(method.getKey())) {
                    continue;
                }
                String label = method.getKey().toUpperCase() + " " + path.getKey();
                JsonNode security = method.getValue().get("security");
                assertTrue(security != null && security.toString().contains("csrfToken"),
                        "X-CSRF-Token is not required on " + label + ": " + security);
                assertTrue(method.getValue().at("/responses/403").isObject()
                                || method.getValue().get("responses").has("403"),
                        "No 403 response declared on " + label);
                asserted++;
            }
        }
        assertTrue(asserted >= 5,
                "Expected at least the five state-changing operations, found " + asserted);
    }
}
