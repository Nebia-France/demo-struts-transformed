package fr.demo.souscription.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * Republication du contrat fournisseur apres la vague 6 (W6-T04).
 *
 * <p>La vague 6 a remplace les deux implementations locales (catalogue, simulation) par des
 * clients REST distants et a pose le mTLS de maillage. Ces changements sont internes : le contrat
 * {@code global/contracts/souscription-gateway-to-souscription-service/openapi.yaml} contre lequel
 * souscription-gateway est code ne doit en avoir subi <b>aucune</b> consequence observable.
 *
 * <p>Ce test est la re-affirmation de cette invariance : il compare l'ensemble observable par
 * l'appelant — operations, chemins, methodes HTTP, statuts de reponse et serveurs — entre le
 * document genere sur {@code /q/openapi} et le contrat gele. Il double volontairement
 * {@link OpenApiContractTest} : celui-ci est la garde de construction du plan 19, celui-ci est la
 * garde de non-regression de la vague 6, et les deux sont executes par l'etape de publication de
 * la pipeline. Le contrat gele n'est jamais modifie.
 */
@QuarkusTest
class ContractRepublishTest {

    private static final Path CONTRACT = Path.of("..", "global", "contracts",
            "souscription-gateway-to-souscription-service", "openapi.yaml");

    private static final List<String> HTTP_METHODS =
            List.of("get", "put", "post", "delete", "patch", "head", "options");

    /** operationId -&gt; "METHODE chemin" et operationId -&gt; statuts declares. */
    private record Surface(Map<String, String> routes, Map<String, TreeSet<String>> statuses) {

        static Surface of(JsonNode document) {
            Map<String, String> routes = new TreeMap<>();
            Map<String, TreeSet<String>> statuses = new TreeMap<>();
            JsonNode paths = document.path("paths");
            for (Iterator<String> it = paths.fieldNames(); it.hasNext(); ) {
                String path = it.next();
                // Le prefixe /v1 est porte par l'URL de serveur de part et d'autre ; la
                // normalisation reste un filet de securite si un @Path le reintroduisait.
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
            return new Surface(routes, statuses);
        }
    }

    private static JsonNode published() {
        String body = given().when().get("/q/openapi?format=json")
                .then().statusCode(200)
                .extract().asString();
        try {
            return new ObjectMapper().readTree(body);
        } catch (IOException unreadable) {
            throw new IllegalStateException("document OpenAPI publie illisible", unreadable);
        }
    }

    private static JsonNode frozenContract() {
        try {
            return new ObjectMapper(new YAMLFactory()).readTree(Files.readString(CONTRACT));
        } catch (IOException unreadable) {
            throw new IllegalStateException("contrat gele illisible : " + CONTRACT, unreadable);
        }
    }

    @Test
    void theSixOperationsStillCarryTheContractPathsAndMethods() {
        Surface contract = Surface.of(frozenContract());
        Surface publishedSurface = Surface.of(published());

        assertEquals(6, contract.routes().size(), "le contrat gele declare six operations");
        assertEquals(contract.routes(), publishedSurface.routes(),
                "apres la vague 6, chemin et methode de chaque operation doivent etre inchanges");
    }

    @Test
    void theResponseStatusesAreUnchangedByTheRemoteClients() {
        Surface contract = Surface.of(frozenContract());
        Surface publishedSurface = Surface.of(published());

        // Le point sensible de la vague 6 : un appel distant qui echoue ne doit pas introduire de
        // statut absent du contrat (ni un 502, ni un 504). Les indisponibilites du catalogue et de
        // la simulation sont traduites dans les statuts deja declares.
        assertEquals(contract.statuses(), publishedSurface.statuses(),
                "l'ensemble des statuts declares doit etre identique, operation par operation");
    }

    @Test
    void theServersRemainTheClusterInternalMtlsEndpoints() {
        List<String> expected = new ArrayList<>();
        frozenContract().path("servers").forEach(s -> expected.add(s.path("url").asText()));
        List<String> actual = new ArrayList<>();
        published().path("servers").forEach(s -> actual.add(s.path("url").asText()));

        assertEquals(expected, actual,
                "les serveurs publies doivent rester les adresses https internes au cluster du "
                        + "contrat gele");
    }
}
