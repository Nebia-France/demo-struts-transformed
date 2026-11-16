package fr.demo.souscription.catalogue.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 37 - inter-application contract validation, provider side.
 *
 * <p>This service is the {@code to} endpoint of both catalogue communications, so it provides two
 * contracts and consumes none. Every assertion below is written against the <em>reference</em>
 * documents (copied into {@code src/test/resources/contracts/}, origins recorded in
 * {@code contracts/ORIGIN.md}), never against the implementation: a field removed from
 * {@code ProductDetail} must fail here even if the implementation and the frozen document are
 * changed together.
 *
 * <p>Two deliberate, recorded divergences between the reference documents and the served document
 * (see the contract-validation section of {@code README.md}):
 * <ul>
 *   <li>the reference serialises {@code annualRate} as a <em>string</em> with six decimals; the
 *       served document types it as a {@code number} with {@code multipleOf} 1e-6 and the wire
 *       literal carries exactly six decimals, which is asserted on the live response here;</li>
 *   <li>the reference declares {@code additionalProperties: false}; the served
 *       {@code ProductDetail} carries the extra optional {@code ratePercent}, which is a
 *       backward-compatible addition under {@code versioningStrategy.compatibility}.</li>
 * </ul>
 */
@QuarkusTest
class InterApplicationContractTest {

    private static final String GATEWAY_CONTRACT =
            "souscription-gateway-to-souscription-catalogue-service";
    private static final String SUBSCRIPTION_CONTRACT =
            "souscription-service-to-souscription-catalogue-service";

    /** The reference documents declare paths relative to the {@code /v1} server base URL. */
    private static final String SERVED_BASE = "/v1";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static JsonNode served;
    private static JsonNode frozen;
    private static String readme;
    private static String properties;

    // @BeforeEach, not @BeforeAll: RestAssured is pointed at the test HTTP port by a Quarkus
    // callback that runs after a static @BeforeAll, so the document must be fetched per test.
    @BeforeEach
    void loadDocuments() throws Exception {
        // The served document is requested as YAML (the format OpenApiContractTest freezes) and
        // parsed into the same tree model as the reference documents.
        String body = given().accept("application/yaml")
                .when().get("/q/openapi")
                .then().statusCode(200).extract().asString();
        served = YAML.readTree(body);
        frozen = YAML.readTree(Files.readString(
                Path.of("src/main/resources/META-INF/openapi-contract-v1.yaml"),
                StandardCharsets.UTF_8));
        readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        properties = Files.readString(Path.of("src/main/resources/application.properties"),
                StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ helpers

    private static InputStream resource(String path) {
        InputStream stream = InterApplicationContractTest.class.getClassLoader()
                .getResourceAsStream(path);
        assertNotNull(stream, "reference resource absente du classpath : " + path);
        return stream;
    }

    private static JsonNode reference(String contractId) throws Exception {
        try (InputStream stream = resource("contracts/" + contractId + "/openapi.yaml")) {
            return YAML.readTree(stream);
        }
    }

    private static JsonNode referenceJson(String path) throws Exception {
        try (InputStream stream = resource("contracts/" + path)) {
            return JSON.readTree(stream);
        }
    }

    /** Resolves the {@code ../shared-schemas/X.json} indirection used by both reference documents. */
    private static JsonNode referenceSchema(String contractId, String schemaName) throws Exception {
        JsonNode declared = reference(contractId).path("components").path("schemas").path(schemaName);
        assertFalse(declared.isMissingNode(),
                "schema " + schemaName + " absent du contrat de reference " + contractId);
        JsonNode ref = declared.get("$ref");
        if (ref == null) {
            return declared;
        }
        String file = ref.asText().substring(ref.asText().lastIndexOf('/') + 1);
        return referenceJson("shared-schemas/" + file);
    }

    /** Resolves a property of a shared schema, following the {@code X.json#/properties/y} form. */
    private static JsonNode referenceProperty(JsonNode schema, String property) throws Exception {
        JsonNode node = schema.path("properties").path(property);
        assertFalse(node.isMissingNode(), "propriete " + property + " absente du schema de reference");
        JsonNode ref = node.get("$ref");
        if (ref == null) {
            return node;
        }
        String[] parts = ref.asText().split("#");
        JsonNode target = referenceJson("shared-schemas/" + parts[0]);
        for (String segment : parts[1].split("/")) {
            if (!segment.isEmpty()) {
                target = target.path(segment);
            }
        }
        return target;
    }

    private static JsonNode servedSchema(String name) {
        JsonNode schema = served.path("components").path("schemas").path(name);
        assertFalse(schema.isMissingNode(), "schema " + name + " absent du document servi");
        return schema;
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String bodyOf(String path) {
        return given().when().get(path).then().statusCode(200).extract().asString();
    }

    // ------------------------------------------------- 1. paths and operations

    private void assertPathsAndOperationsServed(String contractId) throws Exception {
        JsonNode referencePaths = reference(contractId).path("paths");
        assertTrue(referencePaths.size() > 0, "contrat de reference sans chemin : " + contractId);
        for (String referencePath : names(referencePaths)) {
            String servedPath = SERVED_BASE + referencePath;
            JsonNode servedItem = served.path("paths").path(servedPath);
            assertFalse(servedItem.isMissingNode(),
                    "chemin " + servedPath + " du contrat " + contractId + " non servi");
            for (String operation : names(referencePaths.path(referencePath))) {
                if (operation.equals("parameters")) {
                    continue;
                }
                assertFalse(servedItem.path(operation).isMissingNode(),
                        "operation " + operation.toUpperCase() + " " + servedPath
                                + " du contrat " + contractId + " non servie");
            }
        }
    }

    @Test
    void contratPasserelle_tousLesCheminsEtOperationsSontServis() throws Exception {
        assertPathsAndOperationsServed(GATEWAY_CONTRACT);
        // The gateway contract is the full surface: both operations must be present.
        assertFalse(served.path("paths").path("/v1/products").path("get").isMissingNode());
        assertFalse(served.path("paths").path("/v1/products/{code}").path("get").isMissingNode());
    }

    @Test
    void contratSouscription_leCheminDeRechercheProduitEstServi() throws Exception {
        assertPathsAndOperationsServed(SUBSCRIPTION_CONTRACT);
        assertEquals(1, reference(SUBSCRIPTION_CONTRACT).path("paths").size(),
                "le contrat souscription->catalogue ne doit consommer que la fiche produit");
    }

    // ------------------------------------------------------------- 2. schemas

    /**
     * Every property the reference declares required exists in the served schema, is itself
     * declared required there, and carries the same type - with the single recorded exception of
     * {@code annualRate} (reference: scale-preserving string, served: number + multipleOf 1e-6,
     * wire scale asserted in {@link #lesTauxRespectentLesEchellesDuContrat()}).
     */
    private void assertSchemaMatchesReference(String contractId, String schemaName) throws Exception {
        JsonNode referenceSchema = referenceSchema(contractId, schemaName);
        JsonNode servedSchema = servedSchema(schemaName);
        List<String> servedRequired = new ArrayList<>();
        servedSchema.path("required").forEach(n -> servedRequired.add(n.asText()));
        for (JsonNode requiredNode : referenceSchema.path("required")) {
            String property = requiredNode.asText();
            JsonNode servedProperty = servedSchema.path("properties").path(property);
            assertFalse(servedProperty.isMissingNode(), schemaName + "." + property
                    + " requis par " + contractId + " mais absent du document servi");
            assertTrue(servedRequired.contains(property), schemaName + "." + property
                    + " requis par " + contractId + " mais non declare required dans le document servi");
            String referenceType = referenceProperty(referenceSchema, property).path("type").asText();
            String servedType = servedProperty.path("type").asText();
            if (property.equals("annualRate")) {
                assertEquals("number", servedType,
                        "annualRate doit rester numerique sur le fil (divergence enregistree)");
                assertEquals(0, new BigDecimal(servedProperty.path("multipleOf").asText())
                                .compareTo(new BigDecimal("0.000001")),
                        "annualRate doit porter multipleOf 1e-6 pour garantir le DECIMAL(8,6)");
                assertEquals("^[0-9]\\.[0-9]{6}$",
                        referenceProperty(referenceSchema, property).path("pattern").asText(),
                        "le contrat de reference a change d'echelle pour annualRate");
            } else {
                assertEquals(referenceType, servedType,
                        "type divergent pour " + schemaName + "." + property);
            }
        }
    }

    @Test
    void contratPasserelle_productSummaryEtProductDetailCorrespondent() throws Exception {
        assertSchemaMatchesReference(GATEWAY_CONTRACT, "ProductSummary");
        assertSchemaMatchesReference(GATEWAY_CONTRACT, "ProductDetail");
        // exchangedData of the gateway contract, verified name by name.
        for (String schema : List.of("ProductSummary", "ProductDetail")) {
            assertFalse(servedSchema(schema).isMissingNode());
        }
    }

    @Test
    void contratSouscription_productDetailPorteCodeNomEtTauxAnnuel() throws Exception {
        assertSchemaMatchesReference(SUBSCRIPTION_CONTRACT, "ProductDetail");
        JsonNode detail = servedSchema("ProductDetail");
        for (String property : List.of("code", "name", "annualRate")) {
            assertFalse(detail.path("properties").path(property).isMissingNode(),
                    "ProductDetail." + property + " est requis par le chemin de confirmation");
        }
        // The denormalised subscription row also stores the profile, so it must stay present.
        assertFalse(detail.path("properties").path("profile").isMissingNode());
    }

    @Test
    void lesTauxRespectentLesEchellesDuContrat() {
        Pattern annualRate = Pattern.compile("\"annualRate\":([0-9.]+)");
        Matcher list = annualRate.matcher(bodyOf("/v1/products"));
        int count = 0;
        while (list.find()) {
            assertEquals(6, new BigDecimal(list.group(1)).scale(),
                    "annualRate doit porter 6 decimales : " + list.group(1));
            count++;
        }
        assertEquals(3, count, "le catalogue de reference compte trois produits");
        for (String code : List.of("PRUDENT", "EQUILIBRE", "DYNAMIQUE")) {
            String body = bodyOf("/v1/products/" + code);
            Matcher annual = annualRate.matcher(body);
            assertTrue(annual.find(), body);
            assertEquals(6, new BigDecimal(annual.group(1)).scale(), body);
            Matcher percent = Pattern.compile("\"ratePercent\":([0-9.]+)").matcher(body);
            assertTrue(percent.find(), body);
            assertEquals(2, new BigDecimal(percent.group(1)).scale(), body);
        }
    }

    // ------------------------------------------------------- 3. error responses

    private void assertErrorResponsesAreProblemJson(String contractId) throws Exception {
        JsonNode referencePaths = reference(contractId).path("paths");
        for (String referencePath : names(referencePaths)) {
            JsonNode servedOperation = served.path("paths")
                    .path(SERVED_BASE + referencePath).path("get");
            for (String status : names(referencePaths.path(referencePath).path("get")
                    .path("responses"))) {
                if (status.startsWith("2")) {
                    continue;
                }
                JsonNode content = servedOperation.path("responses").path(status).path("content");
                assertFalse(content.isMissingNode(),
                        "reponse " + status + " de " + referencePath + " absente du document servi");
                JsonNode problem = content.path("application/problem+json");
                assertFalse(problem.isMissingNode(), "reponse " + status + " de " + referencePath
                        + " doit etre en application/problem+json");
                assertEquals("#/components/schemas/ProblemDetail",
                        problem.path("schema").path("$ref").asText(),
                        "reponse " + status + " de " + referencePath
                                + " doit referencer le schema ProblemDetail");
            }
        }
    }

    @Test
    void contratPasserelle_lesReponsesDErreurSontEnProblemJson() throws Exception {
        assertErrorResponsesAreProblemJson(GATEWAY_CONTRACT);
    }

    @Test
    void contratSouscription_lesReponsesDErreurSontEnProblemJson() throws Exception {
        assertErrorResponsesAreProblemJson(SUBSCRIPTION_CONTRACT);
    }

    @Test
    void problemDetailCouvreLesChampsRequisDeFieldErrors() throws Exception {
        JsonNode fieldErrors = referenceJson("shared-schemas/FieldErrors.json");
        JsonNode problemDetail = servedSchema("ProblemDetail");
        for (JsonNode required : fieldErrors.path("required")) {
            assertFalse(problemDetail.path("properties").path(required.asText()).isMissingNode(),
                    "ProblemDetail." + required.asText() + " requis par FieldErrors mais absent");
        }
        // 404 served through the reference media type, body included.
        given().when().get("/v1/products/INCONNU")
                .then().statusCode(404)
                .contentType("application/problem+json")
                .body("status", org.hamcrest.Matchers.equalTo(404));
    }

    // -------------------------------------------------- 4. ETag / Cache-Control

    @Test
    void lesReponses200PortentEtagEtCacheControl() {
        for (String path : List.of("/v1/products", "/v1/products/PRUDENT")) {
            given().when().get(path).then().statusCode(200)
                    .header("ETag", org.hamcrest.Matchers.notNullValue())
                    .header("Cache-Control", org.hamcrest.Matchers.containsString("max-age="));
        }
    }

    // ------------------------------------------- 5. backward compatibility of /v1

    /**
     * Fails the build on a backward-incompatible change to {@code /v1}: a removed path, a removed
     * operation, a removed required property or a narrowed type. Only new optional fields are
     * compatible within a major version
     * ({@code versioningStrategy.compatibility} of {@code INTER_APPLICATION_CONTRACTS.json}).
     */
    @Test
    void aucuneRuptureDeCompatibiliteSurV1() {
        List<String> breaks = new ArrayList<>();
        JsonNode frozenPaths = frozen.path("paths");
        for (String path : names(frozenPaths)) {
            JsonNode servedItem = served.path("paths").path(path);
            if (servedItem.isMissingNode()) {
                breaks.add("chemin supprime : " + path);
                continue;
            }
            for (String operation : names(frozenPaths.path(path))) {
                if (operation.equals("parameters")) {
                    continue;
                }
                JsonNode servedOperation = servedItem.path(operation);
                if (servedOperation.isMissingNode()) {
                    breaks.add("operation supprimee : " + operation + " " + path);
                    continue;
                }
                for (String status : names(frozenPaths.path(path).path(operation)
                        .path("responses"))) {
                    if (servedOperation.path("responses").path(status).isMissingNode()) {
                        breaks.add("reponse supprimee : " + status + " sur " + operation + " " + path);
                    }
                }
            }
        }
        JsonNode frozenSchemas = frozen.path("components").path("schemas");
        for (String schemaName : names(frozenSchemas)) {
            JsonNode frozenSchema = frozenSchemas.path(schemaName);
            JsonNode servedSchema = served.path("components").path("schemas").path(schemaName);
            if (servedSchema.isMissingNode()) {
                breaks.add("schema supprime : " + schemaName);
                continue;
            }
            List<String> servedRequired = new ArrayList<>();
            servedSchema.path("required").forEach(n -> servedRequired.add(n.asText()));
            for (JsonNode required : frozenSchema.path("required")) {
                if (!servedRequired.contains(required.asText())) {
                    breaks.add("propriete requise supprimee : " + schemaName + "."
                            + required.asText());
                }
            }
            for (String property : names(frozenSchema.path("properties"))) {
                JsonNode servedProperty = servedSchema.path("properties").path(property);
                if (servedProperty.isMissingNode()) {
                    breaks.add("propriete supprimee : " + schemaName + "." + property);
                    continue;
                }
                String frozenType = frozenSchema.path("properties").path(property)
                        .path("type").asText();
                String servedType = servedProperty.path("type").asText();
                if (!frozenType.equals(servedType)) {
                    breaks.add("type restreint : " + schemaName + "." + property + " "
                            + frozenType + " -> " + servedType);
                }
            }
        }
        assertTrue(breaks.isEmpty(), "rupture(s) de compatibilite sur /v1 : " + breaks);
    }

    // --------------------------------------------- 6. security policy (both contracts)

    @Test
    void lesPolitiquesDeSecuriteDesDeuxContratsSontAppliquees() throws Exception {
        for (String contractId : List.of(GATEWAY_CONTRACT, SUBSCRIPTION_CONTRACT)) {
            JsonNode policy = referenceJson(contractId + "/security-policy.json");
            assertEquals("mTLS", policy.path("authentication").path("mechanism").asText(),
                    contractId + " : mecanisme d'authentification inattendu");
            assertEquals("none", policy.path("authorization").path("model").asText(),
                    contractId + " : aucune autorisation n'existe dans ce systeme");
            assertTrue(policy.path("tls").path("mutual").asBoolean(), contractId + " : mTLS requis");
            assertFalse(policy.path("network").path("publiclyReachable").asBoolean(),
                    contractId + " : le service ne doit pas etre joignable publiquement");
            assertEquals("1.3", policy.path("tls").path("minimumVersion").asText());
        }
        // Applied by the runtime configuration (plan 26).
        assertTrue(properties.contains("quarkus.http.ssl.client-auth=required"),
                "client-auth=required absent de application.properties");
        assertTrue(properties.contains("quarkus.http.insecure-requests=disabled"),
                "aucun listener en clair ne doit rester sur 8443");
        assertTrue(properties.contains("quarkus.http.ssl.protocols=TLSv1.3"),
                "TLS 1.3 non epingle");
        // No authorization layer is introduced (authorization.model = none).
        assertFalse(properties.contains("quarkus.http.auth.policy"),
                "aucune politique d'autorisation ne doit etre declaree");
        // Applied by the manifests (plan 27): no public exposure object at all.
        String manifests = readManifests();
        for (String forbidden : List.of("LoadBalancer", "NodePort", "kind: Ingress")) {
            assertFalse(manifests.contains(forbidden),
                    "objet d'exposition publique trouve dans deploy/ : " + forbidden);
        }
        for (String caller : List.of("souscription-gateway", "souscription-service")) {
            assertTrue(Files.readString(Path.of("deploy/networkpolicy.yaml"), StandardCharsets.UTF_8)
                            .contains("app: " + caller + " }"),
                    "appelant autorise absent de la NetworkPolicy : " + caller);
        }
    }

    private static String readManifests() throws Exception {
        StringBuilder all = new StringBuilder();
        try (var files = Files.list(Path.of("deploy"))) {
            for (Path file : files.toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return all.toString();
    }

    // ------------------------------------------- 7. resilience contract (both contracts)

    @Test
    void lesContratsDeResilienceSontConfrontesAuxEngagementsPublies() throws Exception {
        for (String contractId : List.of(GATEWAY_CONTRACT, SUBSCRIPTION_CONTRACT)) {
            JsonNode resilience = referenceJson(contractId + "/resilience-contract.json");
            assertEquals("99.9%", resilience.path("sla").path("availability").asText(),
                    contractId + " : disponibilite de reference inattendue");
            assertEquals("3s", resilience.path("timeout").path("value").asText(),
                    contractId + " : timeout de reference inattendu");
            assertEquals(2, resilience.path("retry").path("maxRetries").asInt(),
                    contractId + " : nombre de retries de reference inattendu");
        }
        // Published engagement (plan 20) and the recorded divergence from the references.
        assertTrue(readme.contains("99.9%"), "disponibilite non publiee");
        assertTrue(readme.contains("**2 s**") && readme.contains("**1 retry**"),
                "politique appelant (timeout 2 s / 1 retry) non publiee");
        assertTrue(readme.contains("Open points: divergence from the reference contracts"),
                "les divergences avec les contrats de reference ne sont pas enregistrees");
        assertTrue(readme.contains("## Inter-application contract validation (plan 37)"),
                "la section de validation des contrats est absente du README");
        for (String recorded : List.of("2 contracts provided and validated",
                "0 contract consumed")) {
            assertTrue(readme.contains(recorded), "statut final absent du README : " + recorded);
        }
    }

    @Test
    void aucunContratNEstConsomme() throws Exception {
        List<String> clients = new ArrayList<>();
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source, StandardCharsets.UTF_8);
                if (content.contains("@RegisterRestClient") || content.contains("RestClientBuilder")
                        || content.contains("java.net.http.HttpClient")) {
                    clients.add(source.toString());
                }
            }
        }
        assertTrue(clients.isEmpty(),
                "ce service ne consomme aucun contrat, client REST sortant trouve dans : " + clients);
    }

    // --------------------------------------- 8. the copies must not drift from the originals

    @Test
    void lesCopiesDeReferenceNeDeriventPasDesOriginaux() throws Exception {
        Path origin = Path.of("../global/contracts");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(origin),
                "originaux hors du module, verification de derive ignoree");
        Map<String, String> copies = Map.of(
                GATEWAY_CONTRACT + "/openapi.yaml", GATEWAY_CONTRACT + "/openapi.yaml",
                GATEWAY_CONTRACT + "/security-policy.json", GATEWAY_CONTRACT + "/security-policy.json",
                GATEWAY_CONTRACT + "/resilience-contract.json", GATEWAY_CONTRACT + "/resilience-contract.json",
                SUBSCRIPTION_CONTRACT + "/openapi.yaml", SUBSCRIPTION_CONTRACT + "/openapi.yaml",
                SUBSCRIPTION_CONTRACT + "/security-policy.json", SUBSCRIPTION_CONTRACT + "/security-policy.json",
                SUBSCRIPTION_CONTRACT + "/resilience-contract.json", SUBSCRIPTION_CONTRACT + "/resilience-contract.json",
                "shared-schemas/ProductSummary.json", "shared-schemas/ProductSummary.json",
                "shared-schemas/ProductDetail.json", "shared-schemas/ProductDetail.json",
                "shared-schemas/FieldErrors.json", "shared-schemas/FieldErrors.json",
                "INTER_APPLICATION_CONTRACTS.json", "INTER_APPLICATION_CONTRACTS.json");
        for (Map.Entry<String, String> entry : copies.entrySet()) {
            String copy = Files.readString(
                    Path.of("src/test/resources/contracts", entry.getKey()), StandardCharsets.UTF_8);
            String source = Files.readString(origin.resolve(entry.getValue()), StandardCharsets.UTF_8);
            assertEquals(source, copy, "la copie de reference a derive de son original : "
                    + entry.getKey());
        }
    }
}
