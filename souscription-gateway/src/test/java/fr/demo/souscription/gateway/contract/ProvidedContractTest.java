package fr.demo.souscription.gateway.contract;

import static fr.demo.souscription.gateway.contract.ContractDocuments.HTTP_METHODS;
import static fr.demo.souscription.gateway.contract.ContractDocuments.PUBLIC_CONTRACT;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import fr.demo.souscription.gateway.contract.ContractDocuments.Located;
import fr.demo.souscription.gateway.contract.ContractDocuments.Operation;
import fr.demo.souscription.gateway.edge.CsrfTestPair;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Provider side of {@code souscription-web-to-souscription-gateway} (W7-T03).
 *
 * <p>The reference is {@code global/contracts/souscription-web-to-souscription-gateway/openapi.yaml}
 * and the shared JSON schemas it references; souscription-web generates its TypeScript models from
 * it. Two things are compared against it:
 *
 * <ol>
 *   <li>the document the gateway publishes — on {@code /q/openapi} and stored at build time in
 *       {@code target/openapi.yaml} — operation by operation: path, method, {@code operationId},
 *       path parameters, CSRF requirement and every declared response;</li>
 *   <li>the bytes the gateway actually answers on the whole journey, validated against the
 *       reference response schema of the returned status, plus the frozen wave-1 wire format on
 *       the raw text: amounts with scale 2 and the rate with scale 6 as JSON strings, ISO-8601
 *       dates and instants, UTF-8, and the {@code application/problem+json} shape of
 *       {@code FieldErrors.json}.</li>
 * </ol>
 *
 * <p>The reference wins: a divergence is fixed in the gateway, never by relaxing this test.
 */
@QuarkusTest
class ProvidedContractTest {

    private static final Path REFERENCE = ContractDocuments.contractFile(PUBLIC_CONTRACT);

    private static final Path STORED = Path.of("target", "openapi.yaml");

    private static final String PREFIX = "/api/v1";

    private static final String DRAFT = "11111111-1111-1111-1111-111111111111";

    private static final String CONFIRMATION_KEY = "22222222-2222-2222-2222-222222222222";

    private static final String REFERENCE_ID = "33333333-3333-3333-3333-333333333333";

    private static final Pattern AMOUNT = Pattern.compile("^[0-9]{1,6}\\.[0-9]{2}$");

    private static final Pattern RATE = Pattern.compile("^[0-9]\\.[0-9]{6}$");

    @AfterEach
    void resetTheConfirmationScenario() {
        // The confirmation stub is a WireMock scenario (first call 201, replay 200) shared by the
        // whole run: leave it in its initial state.
        DownstreamWireMocks.server("subscription").resetScenarios();
    }

    // ------------------------------------------------------------ published document vs reference

    @Test
    void theGeneratedSpecIsStoredAtBuildTimeAndIsAValidDocument() {
        assertTrue(Files.isRegularFile(STORED),
                "the build must store the provider document at " + STORED.toAbsolutePath());
        JsonNode stored = ContractDocuments.parse(STORED);
        assertTrue(stored.path("openapi").asText().startsWith("3."),
                "not an OpenAPI 3 document: " + stored.path("openapi"));
        assertFalse(stored.path("info").path("title").asText().isBlank(), "info.title missing");
        assertFalse(stored.path("info").path("version").asText().isBlank(), "info.version missing");
        assertTrue(stored.path("paths").size() > 0, "no path in the stored document");

        List<String> dangling = new ArrayList<>();
        collectDanglingReferences(stored, stored, "", dangling);
        assertEquals(List.of(), dangling, "the stored document has unresolvable $ref");

        assertEquals(publicOperations(served()), publicOperations(stored),
                "target/openapi.yaml and /q/openapi must publish the same /api/v1 surface");
    }

    @Test
    void thePublicSurfaceIsExactlyTheReferenceSurface() {
        Map<String, String> expected = new TreeMap<>();
        ContractDocuments.operations(REFERENCE).values().forEach(op ->
                expected.put(op.method().toUpperCase() + " " + PREFIX + op.path(), op.operationId()));

        assertEquals(10, expected.size(), "the reference declares ten operations");
        assertEquals(expected, publicOperations(served()),
                "every reference operation must be published with its operationId, and no other"
                        + " public operation may exist (a missing one breaks souscription-web)");
    }

    @Test
    void everyOperationKeepsItsPathParametersCsrfRequirementAndDeclaredResponses() {
        JsonNode served = served();
        List<String> divergences = new ArrayList<>();
        for (Operation reference : ContractDocuments.operations(REFERENCE).values()) {
            String label = reference.key();
            JsonNode published = served.path("paths").path(PREFIX + reference.path())
                    .path(reference.method());

            TreeSet<String> referenceParams = new TreeSet<>();
            reference.parameters().stream().filter(p -> "path".equals(p.path("in").asText()))
                    .forEach(p -> referenceParams.add(p.get("name").asText()));
            TreeSet<String> publishedParams = new TreeSet<>();
            published.path("parameters").forEach(p -> {
                JsonNode resolved = resolveLocal(served, p);
                if ("path".equals(resolved.path("in").asText())) {
                    publishedParams.add(resolved.get("name").asText());
                }
            });
            if (!referenceParams.equals(publishedParams)) {
                divergences.add(label + ": path parameters " + publishedParams + ", reference "
                        + referenceParams);
            }

            boolean referenceCsrf = reference.node().path("security").toString().contains("csrfToken");
            boolean publishedCsrf = published.path("security").toString().contains("csrfToken");
            if (referenceCsrf != publishedCsrf) {
                divergences.add(label + ": X-CSRF-Token required=" + publishedCsrf
                        + ", reference " + referenceCsrf);
            }

            for (Iterator<Map.Entry<String, JsonNode>> it = reference.node().get("responses").fields();
                    it.hasNext(); ) {
                Map.Entry<String, JsonNode> response = it.next();
                JsonNode publishedResponse = published.path("responses").path(response.getKey());
                if (publishedResponse.isMissingNode()) {
                    divergences.add(label + ": declared response " + response.getKey()
                            + " is not published");
                    continue;
                }
                String referenceMedia = ContractDocuments.mediaType(REFERENCE, response.getValue());
                JsonNode publishedContent = resolveLocal(served, publishedResponse).path("content");
                if (referenceMedia != null && !publishedContent.has(referenceMedia)) {
                    divergences.add(label + " " + response.getKey() + ": media type "
                            + referenceMedia + " not published (" + publishedContent.fieldNames()
                            .hasNext() + ")");
                }
            }
        }
        assertEquals(List.of(), divergences, "published operations diverge from the reference");
    }

    @Test
    void theCsrfSchemeIsTheReferenceHeader() {
        JsonNode reference = ContractDocuments.load(REFERENCE)
                .at("/components/securitySchemes/csrfToken");
        JsonNode published = served().at("/components/securitySchemes/csrfToken");
        assertFalse(published.isMissingNode(), "csrfToken security scheme not published");
        for (String member : List.of("type", "in", "name")) {
            assertEquals(reference.get(member).asText(), published.path(member).asText(),
                    "csrfToken." + member);
        }
        assertEquals("X-CSRF-Token", published.get("name").asText());
    }

    /** The published component schemas carry the members of the shared schemas, no more, no less. */
    @Test
    void theExchangedSchemasKeepTheReferenceMembers() {
        JsonNode published = served().path("components").path("schemas");
        JsonNode referenceSchemas = ContractDocuments.load(REFERENCE).at("/components/schemas");
        List<String> divergences = new ArrayList<>();
        for (Iterator<String> names = referenceSchemas.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if ("FieldErrors".equals(name)) {
                continue; // published as Problem, checked below
            }
            Located reference = ContractDocuments.deref(REFERENCE, referenceSchemas.get(name));
            TreeSet<String> expected = fieldNames(reference.node().path("properties"));
            TreeSet<String> actual = fieldNames(published.path(name).path("properties"));
            if (!expected.equals(actual)) {
                divergences.add(name + ": published " + actual + ", reference " + expected);
            }
        }
        Located fieldErrors = ContractDocuments.deref(REFERENCE, referenceSchemas.get("FieldErrors"));
        JsonNode problem = published.path("Problem");
        assertTrue(fieldNames(fieldErrors.node().path("properties"))
                        .containsAll(fieldNames(problem.path("properties"))),
                "Problem publishes a member FieldErrors.json does not declare: "
                        + fieldNames(problem.path("properties")));
        assertEquals("array", problem.at("/properties/errors/type").asText(),
                "errors must be the FieldErrors array, not a map");
        assertEquals(fieldNames(fieldErrors.node().at("/properties/errors/items/properties")),
                fieldNames(problem.at("/properties/errors/items/properties")),
                "the members of an errors entry diverge from FieldErrors.json");
        assertEquals(List.of(), divergences, "published schemas diverge from the shared schemas");
    }

    // ----------------------------------------------------------- live answers vs reference schemas

    @Test
    void everyJourneyAnswerConformsToTheReferenceSchema() {
        List<String> violations = new ArrayList<>();
        violations.addAll(conforms("GET /csrf-token", given().when().get(PREFIX + "/csrf-token")));
        violations.addAll(conforms("GET /products", given().when().get(PREFIX + "/products")));
        violations.addAll(conforms("GET /products/{code}",
                given().when().get(PREFIX + "/products/EQUILIBRE")));
        violations.addAll(conforms("POST /simulations", CsrfTestPair.withCsrf()
                .contentType("application/json")
                .body("{\"productCode\":\"PRUDENT\",\"initialAmount\":\"1000.00\","
                        + "\"monthlyAmount\":\"100.00\",\"durationYears\":10}")
                .when().post(PREFIX + "/simulations")));
        violations.addAll(conforms("POST /drafts", CsrfTestPair.withCsrf()
                .contentType("application/json").body("{\"productCode\":\"PRUDENT\"}")
                .when().post(PREFIX + "/drafts")));
        violations.addAll(conforms("GET /drafts/{draftId}",
                given().when().get(PREFIX + "/drafts/" + DRAFT)));
        violations.addAll(conforms("PUT /drafts/{draftId}/subscriber", CsrfTestPair.withCsrf()
                .contentType("application/json")
                .body("{\"firstName\":\"Alice\",\"lastName\":\"Durand\","
                        + "\"email\":\"alice@example.com\",\"birthDate\":\"1990-01-15\"}")
                .when().put(PREFIX + "/drafts/" + DRAFT + "/subscriber")));
        violations.addAll(conforms("PUT /drafts/{draftId}/simulation", CsrfTestPair.withCsrf()
                .contentType("application/json")
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put(PREFIX + "/drafts/" + DRAFT + "/simulation")));
        violations.addAll(conforms("POST /drafts/{draftId}/confirmation", CsrfTestPair.withCsrf()
                .contentType("application/json")
                .body("{\"confirmationKey\":\"" + CONFIRMATION_KEY + "\"}")
                .when().post(PREFIX + "/drafts/" + DRAFT + "/confirmation")));
        violations.addAll(conforms("GET /subscriptions/{reference}",
                given().when().get(PREFIX + "/subscriptions/" + REFERENCE_ID)));
        assertEquals(List.of(), violations, "the gateway answers diverge from the reference contract");
    }

    @Test
    void errorAnswersAreProblemJsonWithTheFieldErrorsShape() {
        List<String> violations = new ArrayList<>();
        Response notFound = given().when().get(PREFIX + "/products/INCONNU");
        assertEquals(404, notFound.statusCode(), notFound.asString());
        violations.addAll(conforms("GET /products/{code}", notFound));

        Response invalid = CsrfTestPair.withCsrf().contentType("application/json")
                .body("{\"productCode\":\"PRUDENT\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().post(PREFIX + "/simulations");
        assertEquals(400, invalid.statusCode(), invalid.asString());
        violations.addAll(conforms("POST /simulations", invalid));
        assertTrue(invalid.jsonPath().getList("errors.field").contains("initialAmount"),
                "the rejected field is named in the errors array: " + invalid.asString());

        Response forbidden = given().contentType("application/json")
                .body("{\"productCode\":\"PRUDENT\"}").when().post(PREFIX + "/drafts");
        assertEquals(403, forbidden.statusCode(), forbidden.asString());
        violations.addAll(conforms("POST /drafts", forbidden));

        Response unknownDraft = given().when()
                .get(PREFIX + "/drafts/99999999-9999-9999-9999-999999999999");
        assertEquals(404, unknownDraft.statusCode(), unknownDraft.asString());
        violations.addAll(conforms("GET /drafts/{draftId}", unknownDraft));

        assertEquals(List.of(), violations, "error answers diverge from FieldErrors.json");
        for (Response response : List.of(notFound, invalid, forbidden, unknownDraft)) {
            assertEquals(response.statusCode(), response.jsonPath().getInt("status"),
                    "status member equals the HTTP status");
        }
    }

    // ------------------------------------------------------------------ frozen wave-1 wire format

    @Test
    void decimalsTravelAsStringsWithTheirFrozenScale() {
        String products = given().when().get(PREFIX + "/products").then().statusCode(200)
                .extract().asString();
        JsonNode list = ContractDocuments.parseJson(products);
        assertTrue(list.size() > 0, "empty catalogue");
        list.forEach(product -> {
            assertTrue(product.get("annualRate").isTextual(),
                    "the rate is a JSON string, not a number: " + product);
            assertTrue(RATE.matcher(product.get("annualRate").asText()).matches(),
                    "the rate keeps scale 6: " + product);
        });

        JsonNode simulation = ContractDocuments.parseJson(CsrfTestPair.withCsrf()
                .contentType("application/json")
                .body("{\"productCode\":\"PRUDENT\",\"initialAmount\":\"1000.00\","
                        + "\"monthlyAmount\":\"100.00\",\"durationYears\":10}")
                .when().post(PREFIX + "/simulations").then().statusCode(200).extract().asString());
        for (String amount : List.of("initialAmount", "monthlyAmount", "contributed",
                "finalCapital")) {
            assertTrue(simulation.get(amount).isTextual(),
                    amount + " is a JSON string, not a number: " + simulation);
            assertTrue(AMOUNT.matcher(simulation.get(amount).asText()).matches(),
                    amount + " keeps scale 2: " + simulation);
        }
        assertTrue(simulation.get("durationYears").isIntegralNumber(),
                "durationYears stays an integer: " + simulation);
    }

    @Test
    void datesAndInstantsAreIso8601() {
        JsonNode subscription = ContractDocuments.parseJson(given().when()
                .get(PREFIX + "/subscriptions/" + REFERENCE_ID)
                .then().statusCode(200).extract().asString());
        assertTrue(subscription.at("/subscriber/birthDate").asText().matches("\\d{4}-\\d{2}-\\d{2}"),
                "birthDate is an ISO-8601 date: " + subscription);
        assertTrue(subscription.get("createdAt").isTextual()
                        && subscription.get("createdAt").asText()
                        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})"),
                "createdAt is an ISO-8601 instant, not a timestamp: " + subscription);
    }

    @Test
    void jsonIsUtf8EncodedOnTheWire() {
        Response response = given().when().get(PREFIX + "/products/EQUILIBRE");
        assertEquals(200, response.statusCode());
        String contentType = String.valueOf(response.contentType()).toLowerCase();
        assertTrue(contentType.startsWith("application/json"), contentType);
        assertFalse(contentType.contains("charset") && !contentType.contains("utf-8"),
                "a declared charset must be UTF-8: " + contentType);
        String decoded = new String(response.asByteArray(), StandardCharsets.UTF_8);
        assertTrue(decoded.contains("\u00c9quilibr\u00e9"),
                "accented labels are UTF-8 encoded: " + decoded);
    }

    // ---------------------------------------------------------------------------------- helpers

    /** Validates an answer against the reference response of its status. */
    private static List<String> conforms(String operationKey, Response response) {
        Operation operation = ContractDocuments.operations(REFERENCE).get(operationKey);
        assertNotNull(operation, "no reference operation " + operationKey);
        String status = String.valueOf(response.statusCode());
        JsonNode declared = operation.node().path("responses").path(status);
        if (declared.isMissingNode()) {
            return List.of(operationKey + " answered " + status
                    + ", a status the reference does not declare: " + response.asString());
        }
        List<String> violations = new ArrayList<>();
        String media = ContractDocuments.mediaType(REFERENCE, declared);
        if (media != null && !String.valueOf(response.contentType()).startsWith(media)) {
            violations.add(operationKey + " " + status + ": media type " + response.contentType()
                    + ", reference " + media);
        }
        Located schema = ContractDocuments.contentSchema(REFERENCE, declared);
        if (schema != null) {
            violations.addAll(ContractDocuments.validate(schema.file(), schema.node(),
                    ContractDocuments.parseJson(response.asString()), operationKey + " " + status));
        }
        return violations;
    }

    private static JsonNode served() {
        int port = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class).orElse(9001);
        return ContractDocuments.parseJson(given().port(port).accept("application/json")
                .when().get("/q/openapi").then().statusCode(200).extract().asString());
    }

    private static Map<String, String> publicOperations(JsonNode document) {
        Map<String, String> operations = new TreeMap<>();
        document.path("paths").fields().forEachRemaining(path -> {
            if (!path.getKey().startsWith(PREFIX + "/")) {
                return;
            }
            path.getValue().fields().forEachRemaining(verb -> {
                if (HTTP_METHODS.contains(verb.getKey())) {
                    operations.put(verb.getKey().toUpperCase() + " " + path.getKey(),
                            verb.getValue().path("operationId").asText(null));
                }
            });
        });
        return operations;
    }

    private static JsonNode resolveLocal(JsonNode document, JsonNode node) {
        JsonNode current = node;
        while (current.has("$ref") && current.get("$ref").asText().startsWith("#")) {
            current = document.at(current.get("$ref").asText().substring(1));
        }
        return current;
    }

    private static void collectDanglingReferences(JsonNode document, JsonNode node, String where,
            List<String> dangling) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual() && (!ref.asText().startsWith("#")
                    || document.at(ref.asText().substring(1)).isMissingNode())) {
                dangling.add(where + " -> " + ref.asText());
            }
            node.fields().forEachRemaining(e ->
                    collectDanglingReferences(document, e.getValue(), where + "/" + e.getKey(),
                            dangling));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectDanglingReferences(document, node.get(i), where + "/" + i, dangling);
            }
        }
    }

    private static TreeSet<String> fieldNames(JsonNode node) {
        TreeSet<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
