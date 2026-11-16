package fr.demo.souscription.contract;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import fr.demo.souscription.contract.ContractDocuments.Located;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Assertions cote fournisseur du contrat
 * {@code global/contracts/souscription-gateway-to-souscription-service/openapi.yaml} (plan 42,
 * etape 2), lues depuis le disque et comparees a l'artefact publie
 * {@code target/openapi/openapi.json} (celui qu'archive la pipeline), et non a la seule route
 * {@code /q/openapi} deja couverte par {@code OpenApiContractTest}.
 */
@QuarkusTest
class ProviderContractSpecTest {

    static final Path CONTRACT = ContractDocuments.contract(
            "souscription-gateway-to-souscription-service", "openapi.yaml");

    static final Path PUBLISHED = Path.of("target", "openapi", "openapi.json");

    private static final Set<String> OPERATION_IDS = Set.of("startDraft", "getDraft",
            "putDraftSubscriber", "putDraftSimulation", "confirmDraft", "getSubscription");

    private static final List<String> METHODS =
            List.of("get", "put", "post", "delete", "patch", "head", "options");

    private record Route(String method, String path, TreeSet<String> statuses, JsonNode node) {
    }

    private static Map<String, Route> operations(JsonNode document) {
        Map<String, Route> routes = new TreeMap<>();
        JsonNode paths = document.path("paths");
        for (Iterator<String> it = paths.fieldNames(); it.hasNext(); ) {
            String path = it.next();
            for (String method : METHODS) {
                JsonNode operation = paths.path(path).path(method);
                if (!operation.isMissingNode()) {
                    TreeSet<String> statuses = new TreeSet<>();
                    operation.path("responses").fieldNames().forEachRemaining(statuses::add);
                    routes.put(operation.path("operationId").asText(),
                            new Route(method.toUpperCase(), path, statuses, operation));
                }
            }
        }
        return routes;
    }

    private static JsonNode published() {
        assertTrue(Files.isRegularFile(PUBLISHED),
                "l'artefact publie " + PUBLISHED.toAbsolutePath() + " doit exister");
        return ContractDocuments.read(PUBLISHED);
    }

    private static JsonNode frozen() {
        return ContractDocuments.read(CONTRACT);
    }

    @Test
    void thePublishedArtefactDeclaresExactlyTheSixOperationIds() {
        assertEquals(new TreeSet<>(OPERATION_IDS), new TreeSet<>(operations(published()).keySet()));
        assertEquals(new TreeSet<>(OPERATION_IDS), new TreeSet<>(operations(frozen()).keySet()));
    }

    @Test
    void pathsMethodsAndStatusSetsEqualTheContract() {
        Map<String, Route> contract = operations(frozen());
        Map<String, Route> published = operations(published());
        String serverBase = "/v1";
        for (String id : OPERATION_IDS) {
            Route expected = contract.get(id);
            Route actual = published.get(id);
            String actualPath = actual.path().startsWith(serverBase + "/")
                    ? actual.path().substring(serverBase.length()) : actual.path();
            assertEquals(expected.method(), actual.method(), id + " : methode");
            assertEquals(expected.path(), actualPath, id + " : chemin");
            assertEquals(expected.statuses(), actual.statuses(), id + " : statuts");
        }
    }

    /** Toute reference du contrat, et des schemas partages qu'il atteint, resout sur le disque. */
    @Test
    void everyContractReferenceResolvesIntoSharedSchemas() {
        List<String> unresolved = new ArrayList<>();
        Set<Path> visited = new TreeSet<>();
        List<Path> pending = new ArrayList<>(List.of(CONTRACT));
        while (!pending.isEmpty()) {
            Path file = pending.remove(0);
            if (!visited.add(file)) {
                continue;
            }
            for (String ref : ContractDocuments.refs(ContractDocuments.read(file))) {
                try {
                    ContractDocuments.resolve(file, ref);
                    Path target = ContractDocuments.targetFile(file, ref);
                    if (!target.equals(file)) {
                        if (!target.startsWith(ContractDocuments.SHARED_SCHEMAS)) {
                            unresolved.add(ref + " sort de shared-schemas/ : " + target);
                        }
                        pending.add(target);
                    }
                } catch (RuntimeException dangling) {
                    unresolved.add(dangling.getMessage());
                }
            }
        }
        assertEquals(List.of(), unresolved);

        JsonNode schemas = frozen().path("components").path("schemas");
        for (Iterator<String> it = schemas.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            Path target = ContractDocuments.targetFile(CONTRACT, schemas.get(name).path("$ref").asText());
            assertEquals(ContractDocuments.SHARED_SCHEMAS.resolve(name + ".json"), target,
                    "components.schemas." + name + " doit referencer shared-schemas/" + name + ".json");
        }
        for (String ref : ContractDocuments.refs(published())) {
            assertTrue(ref.startsWith("#/components/schemas/")
                            && published().path("components").path("schemas")
                                    .has(ref.substring("#/components/schemas/".length())),
                    "reference non resolue dans l'artefact publie : " + ref);
        }
    }

    /**
     * Schemas de corps de chaque operation : meme nom de composant de part et d'autre, et memes
     * champs avec le meme type JSON, recursivement dans les objets imbriques.
     */
    @Test
    void bodySchemasMatchSharedSchemasFieldByField() {
        Map<String, Route> contract = operations(frozen());
        Map<String, Route> published = operations(published());
        List<String> mismatches = new ArrayList<>();
        for (String id : OPERATION_IDS) {
            JsonNode expected = contract.get(id).node();
            JsonNode actual = published.get(id).node();
            compareBody(id + " requete",
                    expected.path("requestBody").path("content").path("application/json").path("schema"),
                    actual.path("requestBody").path("content").path("application/json").path("schema"),
                    mismatches);
            for (String status : contract.get(id).statuses()) {
                Located response = ContractDocuments.deref(
                        new Located(CONTRACT, expected.path("responses").path(status)));
                Map.Entry<String, JsonNode> media = response.node().path("content").fields().next();
                JsonNode actualSchema = actual.path("responses").path(status).path("content")
                        .path(media.getKey()).path("schema");
                if (actualSchema.isMissingNode()) {
                    mismatches.add(id + " " + status + " : media " + media.getKey() + " absent");
                    continue;
                }
                compare(id + " " + status, new Located(response.file(), media.getValue().path("schema")),
                        actualSchema, mismatches, 0);
            }
        }
        assertEquals(List.of(), mismatches);
    }

    private void compareBody(String where, JsonNode expected, JsonNode actual, List<String> out) {
        if (expected.isMissingNode() != actual.isMissingNode()) {
            out.add(where + " : presence du corps differente");
            return;
        }
        if (!expected.isMissingNode()) {
            compare(where, new Located(CONTRACT, expected), actual, out, 0);
        }
    }

    private static JsonNode publishedDeref(JsonNode node) {
        JsonNode current = node;
        while (current.has("$ref")) {
            String ref = current.get("$ref").asText();
            current = published().path("components").path("schemas")
                    .path(ref.substring(ref.lastIndexOf('/') + 1));
        }
        return current;
    }

    /** Un champ {@code oneOf: [schema, {type: null}]} se compare a son alternative non nulle. */
    private static Located nonNull(Located schema) {
        Located current = ContractDocuments.deref(schema);
        if (current.node().has("oneOf")) {
            for (JsonNode alternative : current.node().get("oneOf")) {
                if (!"null".equals(alternative.path("type").asText())) {
                    return ContractDocuments.deref(new Located(current.file(), alternative));
                }
            }
        }
        return current;
    }

    private void compare(String where, Located expectedRaw, JsonNode actualRaw, List<String> out,
                         int depth) {
        String expectedRef = expectedRaw.node().path("$ref").asText();
        String actualRef = actualRaw.path("$ref").asText();
        if (expectedRef.startsWith("#/components/schemas/") && !actualRef.isEmpty()
                && !expectedRef.substring(expectedRef.lastIndexOf('/'))
                        .equals(actualRef.substring(actualRef.lastIndexOf('/')))) {
            out.add(where + " : schema contrat=" + expectedRef + " publie=" + actualRef);
        }
        Located expected = nonNull(expectedRaw);
        JsonNode actual = publishedDeref(actualRaw);
        String expectedType = expected.node().path("type").asText();
        String actualType = actual.path("type").asText();
        if (!expectedType.equals(actualType)) {
            out.add(where + " : type contrat=" + expectedType + " publie=" + actualType);
            return;
        }
        if (!"object".equals(expectedType) || depth > 4) {
            return;
        }
        TreeSet<String> expectedFields = new TreeSet<>();
        expected.node().path("properties").fieldNames().forEachRemaining(expectedFields::add);
        TreeSet<String> actualFields = new TreeSet<>();
        actual.path("properties").fieldNames().forEachRemaining(actualFields::add);
        if (!expectedFields.equals(actualFields)) {
            out.add(where + " : champs contrat=" + expectedFields + " publie=" + actualFields);
            return;
        }
        for (String field : expectedFields) {
            compare(where + "." + field,
                    new Located(expected.file(), expected.node().path("properties").path(field)),
                    actual.path("properties").path(field), out, depth + 1);
        }
    }

    private static JsonNode publishedProperty(String schema, String field) {
        return publishedDeref(published().path("components").path("schemas").path(schema)
                .path("properties").path(field));
    }

    private static JsonNode sharedProperty(String schema, String field) {
        return ContractDocuments.resolve(ContractDocuments.SHARED_SCHEMAS.resolve(schema + ".json"),
                "#/properties/" + field).node();
    }

    /** Montants et taux : chaines de part et d'autre, l'echelle etant portee par le motif gele. */
    @ParameterizedTest(name = "{0}.{1}")
    @CsvSource({"SimulationInput,initialAmount,2", "SimulationInput,monthlyAmount,2",
                "SimulationResult,initialAmount,2", "SimulationResult,monthlyAmount,2",
                "SimulationResult,contributed,2", "SimulationResult,finalCapital,2",
                "ProductDetail,annualRate,6", "ProductSummary,annualRate,6"})
    void amountsAndRatesAreScalePreservingStrings(String schema, String field, int scale) {
        JsonNode shared = sharedProperty(schema, field);
        assertEquals("string", shared.path("type").asText());
        assertTrue(shared.path("pattern").asText().endsWith("\\.[0-9]{" + scale + "}$"),
                "le motif gele fixe l'echelle " + scale + " : " + shared.path("pattern"));
        assertEquals("string", publishedProperty(schema, field).path("type").asText(),
                schema + "." + field + " doit etre publie en chaine, jamais en nombre JSON");
    }

    @Test
    void durationYearsIsAnIntegerOnBothSides() {
        assertEquals("integer", sharedProperty("SimulationInput", "durationYears").path("type").asText());
        assertEquals("integer", publishedProperty("SimulationInput", "durationYears").path("type").asText());
        assertEquals("integer", publishedProperty("SimulationResult", "durationYears").path("type").asText());
    }

    @Test
    void birthDateIsAnIsoDate() {
        JsonNode shared = sharedProperty("SubscriberInput", "birthDate");
        JsonNode actual = publishedProperty("SubscriberInput", "birthDate");
        assertEquals("string", actual.path("type").asText());
        assertEquals(shared.path("format").asText(), actual.path("format").asText());
        assertEquals("date", actual.path("format").asText());
    }

    @Test
    void draftStatusIsTheFourValueEnum() {
        JsonNode shared = sharedProperty("DraftView", "status").path("enum");
        JsonNode actual = publishedProperty("DraftView", "status").path("enum");
        assertEquals(4, shared.size());
        assertEquals(shared, actual, "DraftView.status doit publier l'enum gele a quatre valeurs");
    }

    /** {@code additionalProperties: false} : une propriete inconnue sur chaque corps donne 400. */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "POST|/v1/drafts|{\"productCode\":\"PRUDENT\",\"inconnu\":1}",
            "PUT|/v1/drafts/{id}/subscriber|{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                    + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\","
                    + "\"inconnu\":1}",
            "PUT|/v1/drafts/{id}/simulation|{\"initialAmount\":\"1000.00\","
                    + "\"monthlyAmount\":\"100.00\",\"durationYears\":10,\"inconnu\":1}",
            "POST|/v1/drafts/{id}/confirmation|{\"confirmationKey\":"
                    + "\"6f1c2f6e-2b1e-4d0a-9a55-0f6f3e0b8d11\",\"inconnu\":1}"})
    void anUnknownRequestPropertyYields400(String method, String path, String body) {
        String url = path.replace("{id}", UUID.randomUUID().toString());
        given().contentType(ContentType.JSON).body(body)
                .when().request(method, url)
                .then().statusCode(400);
    }
}
