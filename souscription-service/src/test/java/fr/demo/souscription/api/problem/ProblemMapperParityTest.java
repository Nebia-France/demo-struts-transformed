package fr.demo.souscription.api.problem;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.demo.souscription.application.ConfirmationService;
import fr.demo.souscription.application.DraftStateException;
import fr.demo.souscription.application.StorageUnavailableException;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.junit.jupiter.api.Test;

/**
 * Verification de parite du plan 22 : le remplacement de {@code response.sendError(...)} et des
 * pages d'erreur JSP par des mappers {@code application/problem+json} conserve l'information que
 * le source transmettait (BASELINE.md 4.1, 4.5).
 *
 * <p>Ce que la parite exige, au-dela du simple passage au nouvel enveloppe :
 * <ul>
 *   <li>une entree d'erreur <b>par champ rejete</b>, dans l'ordre de declaration
 *       (la {@code LinkedHashMap} du source) ;</li>
 *   <li>les cles de champ {@code accepted} et {@code storage}, sur lesquelles la SPA cable son
 *       comportement ;</li>
 *   <li>le <b>503 rejouable</b> (ecart FG-003), jamais un 500 pour un echec de stockage ;</li>
 *   <li>aucun detail interne dans le corps : ni {@code SQLState}, ni trace, ni texte SQL ;</li>
 *   <li>aucun chemin d'erreur HTML, y compris les pages 404/405 par defaut du framework.</li>
 * </ul>
 */
@QuarkusTest
class ProblemMapperParityTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    /** Code du conflit de mise a jour concurrente du brouillon, cable par le plan 23. */
    private static final String DRAFT_CONCURRENT_UPDATE = "DRAFT_CONCURRENT_UPDATE";

    /** Fragments internes qu'aucun corps de reponse ne doit contenir. */
    private static final List<String> FORBIDDEN = List.of("sqlstate", "at fr.demo",
            "org.hibernate", "select ", "insert into");

    /**
     * Ressource de test declenchant les echecs applicatifs sans base de donnees. Ses operations
     * sont masquees du document OpenAPI publie, qui ne porte que les operations du contrat gele.
     */
    @Path("/test/parity/problems")
    public static class ParityFailingResource {

        @GET
        @Operation(hidden = true)
        @Path("/key-mismatch")
        public String keyMismatch() {
            throw new DraftStateException("confirmationKey",
                    DraftStateException.CONFIRMATION_KEY_MISMATCH,
                    "cle divergente : draftId=" + UUID.randomUUID());
        }

        @GET
        @Operation(hidden = true)
        @Path("/sequence")
        public String sequence() {
            throw new DraftStateException("confirmationKey",
                    ConfirmationService.PRECONDITION_SEQUENCE,
                    ConfirmationService.PRECONDITION_SEQUENCE + " : draftId=" + UUID.randomUUID());
        }

        @GET
        @Operation(hidden = true)
        @Path("/concurrent")
        public String concurrent() {
            throw new DraftStateException("draft", DRAFT_CONCURRENT_UPDATE,
                    "mise a jour concurrente : draftId=" + UUID.randomUUID());
        }

        @GET
        @Operation(hidden = true)
        @Path("/storage")
        public String storage() {
            throw new StorageUnavailableException("enregistrement refuse",
                    new SQLException("insert into subscriptions.subscriptions failed", "08006"));
        }

        @GET
        @Operation(hidden = true)
        @Path("/not-found")
        public String notFound() {
            throw new jakarta.ws.rs.NotFoundException("brouillon inconnu");
        }
    }

    /** Etape 1 : chaque statut d'erreur porte l'enveloppe, et le corps s'accorde au statut HTTP. */
    @Test
    void everyErrorStatusCarriesTheProblemEnvelope() {
        assertProblem(invalidSubscriber(), 400);
        assertProblem(get("/test/parity/problems/not-found"), 404);
        assertProblem(post("/test/parity/problems/not-found"), 405);
        assertProblem(get("/test/parity/problems/sequence"), 409);
    }

    /** Etape 2 : une entree par champ rejete, dans l'ordre de declaration, avec un code non vide. */
    @Test
    void validationErrorsAreOnePerFieldInDeclarationOrder() {
        Response response = invalidSubscriber();
        assertEquals(400, response.getStatusCode());
        JsonNode body = json(response);
        JsonNode errors = body.get("errors");

        assertEquals(4, errors.size(), "une entree par champ rejete : " + errors);
        List<String> fields = new ArrayList<>();
        errors.forEach(error -> fields.add(error.get("field").asText()));
        assertEquals(List.of("firstName", "lastName", "email", "birthDate"), fields,
                "ordre de declaration des champs");
        errors.forEach(error -> assertFalse(error.path("code").asText("").isEmpty(),
                "code stable manquant : " + error));
    }

    /** Etape 3 : aucun corps d'erreur ne divulgue de detail interne. */
    @Test
    void noInternalDetailInAnyBody() {
        Stream.of(invalidSubscriber(),
                        get("/test/parity/problems/not-found"),
                        post("/test/parity/problems/not-found"),
                        get("/test/parity/problems/key-mismatch"),
                        get("/test/parity/problems/sequence"),
                        get("/test/parity/problems/storage"))
                .forEach(ProblemMapperParityTest::assertNoInternals);
    }

    /** Etape 4 : le 503 de stockage est rejouable, au niveau du mapper comme sur le transport. */
    @Test
    void storageProblemShapeIsRetryable() {
        StorageUnavailableException failure = new StorageUnavailableException("refuse",
                new SQLException("insert into subscriptions.subscriptions failed", "08006"));
        jakarta.ws.rs.core.Response mapped = new StorageUnavailableMapper().toResponse(failure);

        assertEquals(503, mapped.getStatus(), "un echec de stockage n'est jamais un 500");
        assertEquals(PROBLEM_JSON, mapped.getMediaType().toString());
        Problem problem = (Problem) mapped.getEntity();
        assertEquals(503, problem.status(), "le status du corps s'accorde au statut HTTP");
        assertEquals("storage", problem.errors().get(0).field());
        assertEquals(StorageUnavailableMapper.STORAGE_UNAVAILABLE_RETRYABLE,
                problem.errors().get(0).code());
        assertEquals("08006", failure.sqlState(), "le SQLState reste disponible pour le journal");

        Response response = get("/test/parity/problems/storage");
        assertEquals(503, response.getStatusCode());
        assertEquals(503, json(response).get("status").asInt());
        assertNoInternals(response);
    }

    /** Etape 6 : les trois cas de conflit d'etat produisent un 409 avec des codes distincts. */
    @Test
    void draftStateConflictsAllMapTo409() {
        String mismatch = conflictCode("/test/parity/problems/key-mismatch");
        String sequence = conflictCode("/test/parity/problems/sequence");
        String concurrent = conflictCode("/test/parity/problems/concurrent");

        assertEquals(DraftStateException.CONFIRMATION_KEY_MISMATCH, mismatch);
        assertEquals(ConfirmationService.PRECONDITION_SEQUENCE, sequence);
        assertEquals(DRAFT_CONCURRENT_UPDATE, concurrent);
        assertEquals(3, Stream.of(mismatch, sequence, concurrent).distinct().count(),
                "chaque cas de conflit porte son propre code");
    }

    /** Etape 7 : aucune reponse d'erreur n'est rendue en HTML. */
    @Test
    void noHtmlErrorBody() {
        Stream.of(invalidSubscriber(),
                        get("/test/parity/problems/not-found"),
                        post("/test/parity/problems/not-found"),
                        get("/test/parity/problems/storage"))
                .forEach(response -> {
                    assertFalse(String.valueOf(response.getContentType()).contains("text/html"),
                            "corps HTML pour le statut " + response.getStatusCode());
                    assertFalse(response.asString().toLowerCase(Locale.ROOT).contains("<html"),
                            "corps HTML pour le statut " + response.getStatusCode());
                });
    }

    /** Etapes 9 et 10 : la forme du corps est exactement celle du schema gele. */
    @Test
    void problemRecordMatchesTheFrozenSchema() throws IOException {
        JsonNode schema = new ObjectMapper().readTree(Files.readString(schemaPath()));
        List<String> expected = new ArrayList<>();
        schema.get("properties").fieldNames().forEachRemaining(expected::add);

        assertEquals(expected, componentNames(Problem.class),
                "les composants du record doivent etre ceux du schema");
        assertTrue(componentNames(Problem.class).contains("correlationId"),
                "le membre correlationId doit exister (null jusqu'au plan 24)");
        assertEquals(List.of("field", "message", "code"),
                componentNames(Problem.FieldError.class));
    }

    private static List<String> componentNames(Class<?> record) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : record.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    private static java.nio.file.Path schemaPath() {
        java.nio.file.Path current = Paths.get("").toAbsolutePath();
        for (; current != null; current = current.getParent()) {
            java.nio.file.Path candidate = current
                    .resolve("global/contracts/shared-schemas/FieldErrors.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("FieldErrors.json introuvable");
    }

    private static Response invalidSubscriber() {
        return given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"\",\"lastName\":\"\",\"email\":\"pas-une-adresse\","
                        + "\"birthDate\":\"12/05/1990\"}")
                .when().put("/v1/drafts/" + UUID.randomUUID() + "/subscriber")
                .then().extract().response();
    }

    private static Response get(String path) {
        return given().when().get(path).then().extract().response();
    }

    private static Response post(String path) {
        return given().when().post(path).then().extract().response();
    }

    private static String conflictCode(String path) {
        Response response = get(path);
        assertProblem(response, 409);
        JsonNode body = json(response);
        assertEquals(409, body.get("status").asInt());
        assertNoInternals(response);
        return body.get("errors").get(0).get("code").asText();
    }

    private static void assertProblem(Response response, int status) {
        assertEquals(status, response.getStatusCode());
        assertTrue(String.valueOf(response.getContentType()).contains(PROBLEM_JSON),
                "content type pour " + status + " : " + response.getContentType());
        assertEquals(status, json(response).get("status").asInt(),
                "le status du corps doit s'accorder au statut HTTP");
    }

    private static JsonNode json(Response response) {
        try {
            JsonNode body = new ObjectMapper().readTree(response.asString());
            assertNotNull(body, "corps problem+json attendu");
            return body;
        } catch (IOException e) {
            throw new IllegalStateException("corps non JSON : " + response.asString(), e);
        }
    }

    private static void assertNoInternals(Response response) {
        String body = response.asString().toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN) {
            assertFalse(body.contains(forbidden),
                    "detail interne '" + forbidden + "' dans le corps : " + response.asString());
        }
    }
}
