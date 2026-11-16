package fr.demo.souscription.api.validation;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * Un montant hors bornes ou malforme est une erreur de contrainte : le service repond un
 * <b>400</b> structure en {@code application/problem+json}, jamais un 500 (W4-T03, critere 6).
 *
 * <p>Les verdicts eux-memes sont contractes par {@link AmountBoundaryContractTest} ; ce test ne
 * verifie que la forme de la reponse HTTP.
 */
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class AmountBoundaryProblemJsonTest {

    /** Le separateur decimal francais n'est jamais ecrit en litteral dans le module (W3-T07). */
    private static final String COMMA = ",";

    private static final String SUBSCRIBER_ISO = "{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
            + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1990-05-12\"}";

    private String draftWithSubscriber() {
        String draftId = given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts")
                .then().statusCode(201)
                .extract().path("draftId");

        given().contentType(ContentType.JSON)
                .body(SUBSCRIBER_ISO)
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(200);
        return draftId;
    }

    private void expectsProblemJson400(String simulationBody) {
        given().contentType(ContentType.JSON)
                .body(simulationBody)
                .when().put("/v1/drafts/" + draftWithSubscriber() + "/simulation")
                .then().statusCode(400)
                .contentType(containsString("application/problem+json"));
    }

    @Test
    void anOutOfBoundsInitialAmountYields400ProblemJson() {
        expectsProblemJson400("{\"initialAmount\":\"99.99\",\"monthlyAmount\":\"100.00\","
                + "\"durationYears\":5}");
    }

    @Test
    void anOutOfBoundsMonthlyAmountYields400ProblemJson() {
        expectsProblemJson400("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"2000.01\","
                + "\"durationYears\":5}");
    }

    @Test
    void aMalformedAmountYields400ProblemJsonAndNever500() {
        expectsProblemJson400("{\"initialAmount\":\"1000" + COMMA + "00\","
                + "\"monthlyAmount\":\"100.00\",\"durationYears\":5}");
    }

    @Test
    void anOutOfBoundsDurationYields400ProblemJson() {
        expectsProblemJson400("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                + "\"durationYears\":31}");
    }
}
