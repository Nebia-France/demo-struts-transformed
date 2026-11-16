package fr.demo.souscription.gateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity check for 09-souscription-gateway-openapi-contract.
 *
 * <p>The source repository contains no contract document, so the reference is the committed
 * {@code global/contracts/souscription-web-to-souscription-gateway/openapi.yaml}. That file is
 * authoritative: on any divergence the generated document must be corrected, not the reference.
 *
 * <p>This is the cross-application unblocking milestone — souscription-web generates its TypeScript
 * models from this document, so a missing {@code operationId} or a wrong scalar type is a silent
 * breaking change.
 *
 * <p>Two documented deviations from the skeleton:
 * <ul>
 *   <li>{@code /q/openapi} and Swagger UI are served on the <em>management</em> interface
 *       ({@code quarkus.smallrye-openapi.management.enabled=true}), not on the application port, so
 *       the document is fetched on the management test port. The security property asserted by the
 *       skeleton is preserved and strengthened: the document and the UI are both absent from the
 *       public application port.</li>
 *   <li>{@code GET /csrf-token} / {@code issueCsrfToken} is served since plan 15 and is asserted
 *       like every other operation: there is no excluded operation left.</li>
 * </ul>
 */
@QuarkusTest
class OpenApiSurfaceParityTest {

    private static final Path REFERENCE =
            Path.of("..", "global", "contracts",
                    "souscription-web-to-souscription-gateway", "openapi.yaml");

    private static final Pattern OPERATION_ID = Pattern.compile("operationId:\\s*(\\S+)");
    private static final Pattern PATH_ENTRY = Pattern.compile("^\\s{2}(/[^:\\s]+):");

    /** Public prefix applied to the reference paths, which are relative to the server URL. */
    private static final String PUBLIC_PREFIX = "/api/v1";

    private static final String CSRF_OPERATION_ID = "issueCsrfToken";
    private static final String CSRF_PATH = "/csrf-token";

    private static int managementPort() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class)
                .orElse(9001);
    }

    private static String generatedDocument() {
        return given().port(managementPort())
                .when().get("/q/openapi?format=JSON")
                .then().statusCode(200)
                .extract().body().asString();
    }

    @Test
    void openApiDocumentIsPublished() {
        given().port(managementPort()).when().get("/q/openapi?format=JSON")
                .then().statusCode(200);
    }

    @Test
    void everyReferenceOperationIdIsGenerated() throws Exception {
        String reference = Files.readString(REFERENCE);
        String generated = generatedDocument();

        Set<String> expected = extract(OPERATION_ID, reference);
        assertFalse(expected.isEmpty(),
                "the reference contract declares no operationId - wrong path?");

        int asserted = 0;
        for (String operationId : expected) {
            asserted++;
            assertTrue(generated.contains("\"" + operationId + "\""),
                    "operationId missing from the generated document: " + operationId
                            + " (renaming it removes the generated SPA client method)");
        }
        assertEquals(10, asserted,
                "the reference contract is expected to declare 10 operations, including "
                        + CSRF_OPERATION_ID);
    }

    @Test
    void everyReferencePathIsGenerated() throws Exception {
        List<String> lines = Files.readAllLines(REFERENCE);
        String generated = generatedDocument();

        Set<String> paths = new TreeSet<>();
        for (String line : lines) {
            Matcher matcher = PATH_ENTRY.matcher(line);
            if (matcher.find()) {
                paths.add(matcher.group(1));
            }
        }
        assertFalse(paths.isEmpty(), "no path parsed from the reference contract");

        for (String path : paths) {
            assertTrue(generated.contains("\"" + PUBLIC_PREFIX + path + "\""),
                    "public path missing from the generated document: " + PUBLIC_PREFIX + path);
        }
    }

    /**
     * The code must never define the public surface on its own: an operation absent from the
     * reference contract is a contract break that has to be reviewed, not published.
     */
    @Test
    void noGeneratedOperationIsAbsentFromTheReference() throws Exception {
        String reference = Files.readString(REFERENCE);
        Set<String> allowed = extract(OPERATION_ID, reference);

        for (String operationId : extract(Pattern.compile("\"operationId\"\\s*:\\s*\"([^\"]+)\""),
                generatedDocument())) {
            assertTrue(allowed.contains(operationId),
                    "operation published but absent from the reference contract: " + operationId
                            + " (remove the route or amend the contract in a reviewed commit)");
        }
    }

    @Test
    void errorResponsesAreDeclared() {
        String body = generatedDocument();

        // The 7 web.xml error-page statuses must keep a documented public meaning.
        assertTrue(body.contains("\"404\""), "404 must be declared");
        assertTrue(body.contains("\"405\""), "405 must be declared (method allow-list)");
        assertTrue(body.contains("\"400\"") || body.contains("\"403\""),
                "the structural-validation / CSRF statuses must be declared");
    }

    /**
     * Monetary amounts and the annual rate are JSON strings so the BigDecimal scales survive the
     * wire (check 08). If the document declares them as {@code number}, the SPA generates
     * {@code number} models and the scale is lost on the consumer side.
     */
    @Test
    void amountsAndRateAreDeclaredAsScaleSafeStrings() throws Exception {
        JsonNode schemas = new ObjectMapper().readTree(generatedDocument())
                .at("/components/schemas");
        assertFalse(schemas.isMissingNode(), "the document publishes no schemas");

        Set<String> scaled = Set.of("initialAmount", "monthlyAmount", "contributed",
                "finalCapital", "annualRate");
        int asserted = 0;
        for (JsonNode schema : schemas) {
            JsonNode properties = schema.get("properties");
            if (properties == null) {
                continue;
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> property = it.next();
                if (!scaled.contains(property.getKey()) || property.getValue().has("$ref")) {
                    continue;
                }
                asserted++;
                JsonNode type = property.getValue().get("type");
                assertEquals("string", type == null ? null : type.asText(),
                        property.getKey() + " must be declared as a string to preserve its"
                                + " BigDecimal scale");
                assertTrue(property.getValue().has("pattern"),
                        property.getKey() + " must declare its scale pattern");
            }
        }
        assertTrue(asserted >= scaled.size(),
                "expected at least " + scaled.size() + " scaled properties, asserted " + asserted);
    }

    @Test
    void csrfOperationIsPartOfTheGeneratedSurface() {
        assertTrue(generatedDocument().contains("\"" + PUBLIC_PREFIX + CSRF_PATH + "\""),
                "GET /api/v1/csrf-token must belong to the generated public surface (W3-T02)");
    }

    @Test
    void generatedDocumentIsArchivedAsABuildArtefact() {
        Path artefact = Path.of("target", "openapi.yaml");
        assertTrue(Files.exists(artefact),
                "target/openapi.yaml must be produced by store-schema-directory=target; "
                        + "without a versioned artefact the consumer has nothing to pin against");
    }

    @Test
    void swaggerUiIsNotPubliclyExposed() {
        assertEquals(404, given().when().get("/q/swagger-ui").getStatusCode(),
                "Swagger UI must be enabled only under the dev profile: a public UI exposes the"
                        + " whole internal map");
        assertEquals(404, given().when().get("/q/openapi").getStatusCode(),
                "the OpenAPI document must not be served on the public application port");
    }

    private static Set<String> extract(Pattern pattern, String text) {
        Set<String> values = new TreeSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }
}
