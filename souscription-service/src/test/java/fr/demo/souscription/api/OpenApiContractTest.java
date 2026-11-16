package fr.demo.souscription.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Garde de construction du document OpenAPI publie (W3-T09).
 *
 * <p>Compare le document genere sur {@code /q/openapi} au contrat gele
 * {@code global/contracts/souscription-gateway-to-souscription-service/openapi.yaml}, qui est la
 * reference contre laquelle {@code souscription-gateway} est code. Le contrat n'est jamais
 * modifie : c'est le service qui s'y conforme.
 *
 * <p>Le diff textuel brut des deux fichiers n'est pas un critere utilisable (le contrat utilise des
 * {@code $ref} vers {@code shared-schemas/} que le generateur inline). Ce qui est compare est
 * l'ensemble observable par l'appelant : identifiants d'operation, chemin, methode HTTP, ensemble
 * des statuts de reponse et liste des serveurs. Le prefixe {@code /v1} est porte par l'URL de
 * serveur de part et d'autre ({@code VersionPrefixFilter} le retire des chemins du document) ; la
 * normalisation ci-dessous reste en place comme filet de securite.
 */
@QuarkusTest
class OpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "global", "contracts",
            "souscription-gateway-to-souscription-service", "openapi.yaml");

    private static final List<String> HTTP_METHODS =
            List.of("get", "put", "post", "delete", "patch", "head", "options");

    private JsonNode published() {
        String body = given().when().get("/q/openapi?format=json")
                .then().statusCode(200)
                .extract().asString();
        try {
            return new ObjectMapper().readTree(body);
        } catch (IOException unreadable) {
            throw new IllegalStateException("document OpenAPI publie illisible", unreadable);
        }
    }

    private JsonNode contract() {
        try {
            return new ObjectMapper(new YAMLFactory()).readTree(Files.readString(CONTRACT));
        } catch (IOException unreadable) {
            throw new IllegalStateException("contrat gele illisible : " + CONTRACT, unreadable);
        }
    }

    /** operationId -&gt; "METHOD chemin-normalise" et operationId -&gt; statuts declares. */
    private static void index(JsonNode document,
                              Map<String, String> routes,
                              Map<String, TreeSet<String>> statuses) {
        JsonNode paths = document.path("paths");
        for (Iterator<String> it = paths.fieldNames(); it.hasNext(); ) {
            String path = it.next();
            String normalised = path.startsWith("/v1/") ? path.substring(3) : path;
            JsonNode item = paths.path(path);
            for (String method : HTTP_METHODS) {
                JsonNode operation = item.path(method);
                if (operation.isMissingNode()) {
                    continue;
                }
                String operationId = operation.path("operationId").asText();
                routes.put(operationId, method.toUpperCase() + " " + normalised);
                TreeSet<String> codes = new TreeSet<>();
                operation.path("responses").fieldNames().forEachRemaining(codes::add);
                statuses.put(operationId, codes);
            }
        }
    }

    @Test
    void theOperationIdsAreExactlyTheSixContractOperations() {
        Map<String, String> publishedRoutes = new TreeMap<>();
        Map<String, TreeSet<String>> publishedStatuses = new TreeMap<>();
        index(published(), publishedRoutes, publishedStatuses);

        Map<String, String> contractRoutes = new TreeMap<>();
        Map<String, TreeSet<String>> contractStatuses = new TreeMap<>();
        index(contract(), contractRoutes, contractStatuses);

        assertEquals(6, contractRoutes.size(), "le contrat gele declare six operations");
        assertEquals(contractRoutes.keySet(), publishedRoutes.keySet(),
                "les operationId publies doivent etre exactement ceux du contrat gele");
    }

    @Test
    void everyOperationKeepsTheContractPathAndMethod() {
        Map<String, String> publishedRoutes = new TreeMap<>();
        Map<String, TreeSet<String>> ignored = new TreeMap<>();
        index(published(), publishedRoutes, ignored);

        Map<String, String> contractRoutes = new TreeMap<>();
        Map<String, TreeSet<String>> alsoIgnored = new TreeMap<>();
        index(contract(), contractRoutes, alsoIgnored);

        assertEquals(contractRoutes, publishedRoutes,
                "chemin et methode HTTP de chaque operation doivent etre ceux du contrat gele");
    }

    @Test
    void everyOperationDeclaresTheContractResponseStatuses() {
        Map<String, String> routes = new TreeMap<>();
        Map<String, TreeSet<String>> publishedStatuses = new TreeMap<>();
        index(published(), routes, publishedStatuses);

        Map<String, String> contractRoutes = new TreeMap<>();
        Map<String, TreeSet<String>> contractStatuses = new TreeMap<>();
        index(contract(), contractRoutes, contractStatuses);

        assertEquals(contractStatuses, publishedStatuses,
                "l'ensemble des statuts declares doit etre identique, operation par operation");
    }

    @Test
    void theServersAreTheClusterInternalEndpointsOfTheContract() {
        List<String> published = new ArrayList<>();
        published().path("servers").forEach(server -> published.add(server.path("url").asText()));
        List<String> expected = new ArrayList<>();
        contract().path("servers").forEach(server -> expected.add(server.path("url").asText()));

        assertEquals(expected, published,
                "les serveurs publies doivent etre les adresses internes au cluster du contrat gele "
                        + "et non une base localhost auto-detectee");
    }

    @Test
    void everyReferencedSchemaResolves() {
        JsonNode document = published();
        List<String> dangling = new ArrayList<>();
        collectRefs(document, dangling, document);
        assertTrue(dangling.isEmpty(), "references non resolues : " + dangling);
    }

    private static void collectRefs(JsonNode node, List<String> dangling, JsonNode root) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual() && !resolves(ref.asText(), root)) {
                dangling.add(ref.asText());
            }
            node.fields().forEachRemaining(entry -> collectRefs(entry.getValue(), dangling, root));
        } else if (node.isArray()) {
            node.forEach(child -> collectRefs(child, dangling, root));
        }
    }

    private static boolean resolves(String reference, JsonNode root) {
        if (!reference.startsWith("#/")) {
            return false;
        }
        JsonNode current = root;
        for (String segment : reference.substring(2).split("/")) {
            current = current.path(segment.replace("~1", "/").replace("~0", "~"));
            if (current.isMissingNode()) {
                return false;
            }
        }
        return true;
    }
}
