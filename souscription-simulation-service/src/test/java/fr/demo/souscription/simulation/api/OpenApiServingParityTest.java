package fr.demo.souscription.simulation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Parité 23 : le document *servi* sur le port de management doit rester celui que l'étape
 * openapi-contract-diff (plan 10) compare aux deux contrats figés. Le déplacement du document
 * vers l'interface de management ne doit rien changer à son contenu : les deux pipelines
 * consommateurs (souscription-gateway et souscription-service) génèrent leur client à partir de
 * lui.
 *
 * <p>Les noms de champs assertés sont ceux de global/contracts/shared-schemas (BASELINE.md §5) :
 * jamais {@code gains} sur le fil, jamais les noms de prose de la vague 2
 * ({@code contributedTotal}, {@code projectedCapital}).
 */
@QuarkusTest
@Tag("contract")
class OpenApiServingParityTest {

    /** Échelle 2 obligatoire ; le nombre de chiffres entiers varie entre entrées et dérivés. */
    private static final Pattern SCALE_TWO = Pattern.compile("^\\^\\[0-9]\\{1,\\d+}\\\\\\.\\[0-9]\\{2}\\$$");

    @TestHTTPResource(value = "openapi", management = true)
    URL managementOpenApi;

    private JsonPath servedDocument() throws Exception {
        Response response = RestAssured.given().accept("application/json")
                .when().get(managementOpenApi + "?format=json");
        assertEquals(200, response.statusCode(), "le document doit être servi sur le port de management");
        Files.writeString(Path.of("target", "openapi-served.json"), response.asString());
        return response.jsonPath();
    }

    @Test
    void servedDocumentCarriesTheFrozenOperation() throws Exception {
        JsonPath document = servedDocument();
        assertEquals("1.0.0", document.getString("info.version"));
        assertEquals("computeSimulation",
                document.getString("paths.'/v1/simulations'.post.operationId"));
        assertTrue(document.getList("paths.'/v1/simulations'.post.parameters.name", String.class)
                        .containsAll(List.of("X-Correlation-Id", "traceparent")),
                "les deux en-têtes contractuels doivent être déclarés");
        assertEquals(List.of("application/problem+json"),
                mediaTypes(document, "400"));
        assertEquals(List.of("application/problem+json"),
                mediaTypes(document, "500"));
        assertEquals(List.of("application/json"),
                mediaTypes(document, "200"));
    }

    @Test
    void servedDocumentCarriesTheFrozenWireNames() throws Exception {
        JsonPath document = servedDocument();

        Map<String, ?> request = document.getMap("components.schemas.SimulationComputeRequest.properties");
        assertEquals(List.of("annualRate", "durationYears", "initialAmount", "monthlyAmount"),
                request.keySet().stream().sorted().toList());
        assertEquals(List.of("annualRate", "durationYears", "initialAmount", "monthlyAmount"),
                document.getList("components.schemas.SimulationComputeRequest.required", String.class)
                        .stream().sorted().toList());

        Map<String, ?> result = document.getMap("components.schemas.SimulationResult.properties");
        assertEquals(List.of("contributed", "durationYears", "finalCapital", "initialAmount", "monthlyAmount"),
                result.keySet().stream().sorted().toList());
        assertEquals(List.of("contributed", "durationYears", "finalCapital", "initialAmount", "monthlyAmount"),
                document.getList("components.schemas.SimulationResult.required", String.class)
                        .stream().sorted().toList());

        assertFalse(result.containsKey("gains"), "gains n'est jamais publié sur le fil");
        assertFalse(result.containsKey("contributedTotal"), "nom de prose vague 2 interdit");
        assertFalse(result.containsKey("projectedCapital"), "nom de prose vague 2 interdit");
    }

    @Test
    void servedDocumentKeepsTheScaleTwoPattern() throws Exception {
        JsonPath document = servedDocument();
        for (String field : List.of("initialAmount", "monthlyAmount", "contributed", "finalCapital")) {
            String pattern = document.getString(
                    "components.schemas.SimulationResult.properties." + field + ".pattern");
            assertTrue(pattern != null && SCALE_TWO.matcher(pattern).matches(),
                    "le motif d'échelle 2 doit survivre dans le document servi pour " + field
                            + ", trouvé : " + pattern);
            assertEquals(0.01f, document.getFloat(
                    "components.schemas.SimulationResult.properties." + field + ".multipleOf"));
        }
    }

    /**
     * Le document servi et l'artefact publié (target/openapi.yaml, diffé par l'étape
     * openapi-contract-diff) sont comparés sémantiquement : même document, à la seule mise en
     * forme YAML près. Si l'un dérivait de l'autre, le diff bloquant ne garantirait plus rien
     * sur ce que les deux pipelines consommateurs récupèrent réellement.
     */
    @Test
    void servedDocumentMatchesThePublishedArtefact() throws Exception {
        Path stored = Path.of("target", "openapi.yaml");
        assertTrue(Files.exists(stored),
                "quarkus.smallrye-openapi.store-schema-directory doit écrire target/openapi.yaml");
        JsonNode published = new ObjectMapper(new YAMLFactory()).readTree(stored.toFile());
        JsonNode served = new ObjectMapper().readTree(servedDocument().prettify());
        assertEquals(published, served,
                "le document servi sur le port de management doit être celui que le pipeline diffe");
    }

    private static List<String> mediaTypes(JsonPath document, String status) {
        return document.getMap("paths.'/v1/simulations'.post.responses.'" + status + "'.content")
                .keySet().stream().map(String::valueOf).sorted().toList();
    }

}
