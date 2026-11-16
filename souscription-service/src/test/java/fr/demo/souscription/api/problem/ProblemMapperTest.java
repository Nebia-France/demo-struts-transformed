package fr.demo.souscription.api.problem;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import fr.demo.souscription.application.ConfirmationService;
import fr.demo.souscription.application.DraftStateException;
import fr.demo.souscription.application.StorageUnavailableException;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.sql.SQLException;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.junit.jupiter.api.Test;

/**
 * W4-T04 : aucune reponse d'erreur n'echappe du service autrement qu'en
 * {@code application/problem+json}, pour les cinq statuts 400 / 404 / 405 / 409 / 503.
 *
 * <p>Les deux echecs applicatifs (conflit d'etat et indisponibilite du stockage) sont declenches
 * par une ressource de test dediee : les mappers sont ainsi verifies sans base de donnees, donc
 * sans daemon de conteneurs. Les chemins REST reels qui produisent ces statuts sont, eux,
 * contractes par {@code ConfirmationResourceTest}.
 *
 * <p>Verifie aussi qu'aucun corps ne divulgue d'internes : ni {@code SQLState}, ni trace d'appel.
 */
@QuarkusTest
class ProblemMapperTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    /**
     * Ressource de test : declenche les echecs applicatifs sans toucher la base. Ses operations
     * sont masquees du document OpenAPI publie, qui ne doit contenir que les six operations du
     * contrat gele.
     */
    @Path("/test/problems")
    public static class FailingResource {

        @GET
        @Operation(hidden = true)
        @Path("/draft-state")
        public String draftState() {
            throw new DraftStateException("confirmationKey",
                    ConfirmationService.PRECONDITION_SEQUENCE,
                    ConfirmationService.PRECONDITION_SEQUENCE + " : draftId=" + UUID.randomUUID());
        }

        @GET
        @Operation(hidden = true)
        @Path("/storage")
        public String storage() {
            throw new StorageUnavailableException("enregistrement refuse",
                    new SQLException("INSERT INTO subscriptions.subscriptions failed", "08006"));
        }

        @GET
        @Operation(hidden = true)
        @Path("/not-found")
        public String notFound() {
            throw new jakarta.ws.rs.NotFoundException("brouillon inconnu");
        }

        @GET
        @Operation(hidden = true)
        @Path("/bad-request")
        public String badRequest() {
            throw new jakarta.ws.rs.BadRequestException("corps invalide");
        }
    }

    private static void assertsNoInternals(Response response) {
        String body = response.asString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("SQLState"),
                "le corps ne doit pas exposer le SQLState : " + body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("at fr.demo"),
                "le corps ne doit pas exposer de trace d'appel : " + body);
    }

    @Test
    void aValidationFailureIsA400ProblemJsonListingEveryRejectedFieldInOrder() {
        Response response = given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"\",\"lastName\":\"\",\"email\":\"pas-une-adresse\","
                        + "\"birthDate\":\"12/05/1990\"}")
                .when().put("/v1/drafts/" + UUID.randomUUID() + "/subscriber")
                .then().statusCode(400)
                .contentType(containsString(PROBLEM_JSON))
                .body("status", equalTo(400))
                .body("errors.field", contains("firstName", "lastName", "email", "birthDate"))
                .body("errors.code", contains("FIRST_NAME_INVALID", "LAST_NAME_INVALID",
                        "EMAIL_INVALID", "BIRTH_DATE_FORMAT"))
                .body("title", not(equalTo(null)))
                .extract().response();

        assertsNoInternals(response);
    }

    @Test
    void aMissingConfirmationKeyIsA400CarryingTheAcceptanceCode() {
        Response response = given().contentType(ContentType.JSON)
                .body("{}")
                .when().post("/v1/drafts/" + UUID.randomUUID() + "/confirmation")
                .then().statusCode(400)
                .contentType(containsString(PROBLEM_JSON))
                .body("status", equalTo(400))
                .body("errors.field", contains("accepted"))
                .body("errors.code", contains("ACCEPTANCE_REQUIRED"))
                .extract().response();

        assertsNoInternals(response);
    }

    @Test
    void aNotFoundIsA404ProblemJson() {
        Response response = given().when().get("/test/problems/not-found")
                .then().statusCode(404)
                .contentType(containsString(PROBLEM_JSON))
                .body("status", equalTo(404))
                .extract().response();

        assertsNoInternals(response);
    }

    @Test
    void aBadRequestRaisedByJaxRsIsA400ProblemJson() {
        Response response = given().when().get("/test/problems/bad-request")
                .then().statusCode(400)
                .contentType(containsString(PROBLEM_JSON))
                .body("status", equalTo(400))
                .extract().response();

        assertsNoInternals(response);
    }

    @Test
    void aMethodNotAllowedIsA405ProblemJson() {
        Response response = given().when().post("/test/problems/not-found")
                .then().statusCode(405)
                .contentType(containsString(PROBLEM_JSON))
                .body("status", equalTo(405))
                .extract().response();

        assertsNoInternals(response);
    }

    @Test
    void aDraftStateConflictIsA409ProblemJson() {
        Response response = given().when().get("/test/problems/draft-state")
                .then().statusCode(409)
                .contentType(containsString(PROBLEM_JSON))
                .body("status", equalTo(409))
                .body("errors.code", contains(ConfirmationService.PRECONDITION_SEQUENCE))
                .extract().response();

        assertsNoInternals(response);
    }

    /** Un echec de stockage est rejouable et n'est jamais un 500. */
    @Test
    void aStorageFailureIsA503ProblemJsonWithTheRetryableCode() {
        Response response = given().when().get("/test/problems/storage")
                .then().statusCode(503)
                .contentType(containsString(PROBLEM_JSON))
                .body("status", equalTo(503))
                .body("errors[0].field", equalTo("storage"))
                .body("errors[0].code",
                        equalTo(StorageUnavailableMapper.STORAGE_UNAVAILABLE_RETRYABLE))
                .extract().response();

        assertsNoInternals(response);
        org.junit.jupiter.api.Assertions.assertFalse(response.asString().contains("08006"),
                "le corps ne doit pas exposer le SQLState");
        org.junit.jupiter.api.Assertions.assertFalse(response.asString().contains("INSERT"),
                "le corps ne doit pas exposer de texte SQL");
    }
}
