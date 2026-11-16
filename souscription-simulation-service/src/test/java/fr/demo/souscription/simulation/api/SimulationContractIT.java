package fr.demo.souscription.simulation.api;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON contract integration test of {@code POST /v1/simulations} (W7-T03).
 *
 * <p>Replaces the HTML-parsing assertions the legacy
 * {@code src/test/java/fr/demo/souscription/web/SubscriptionIT.java} made on {@code /simulation.do}
 * (the {@code #capital-final} lookup and the form-field round-trip) by assertions on the JSON
 * document of the frozen contract. The token, multi-step navigation and server-side state the
 * legacy test carried are deliberately not ported: those web controls belong to
 * {@code souscription-gateway} and {@code souscription-web}, this service is stateless and
 * authentication-less behind mTLS.
 *
 * <p>Carries {@code @Tag("contract")}, which is how a test joins the single reusable
 * contract-test suite declared in {@code CONTRACT.md} ({@code mvn -Pcontract-suite test}).
 */
@QuarkusTest
@Tag("contract")
class SimulationContractIT {

    /** The contract pattern of the monetary fields: scale 2, dot separator, no exponent. */
    private static final String MONEY = "^[0-9]{1,12}\\.[0-9]{2}$";

    /** The French messages of {@code SimulationRequest}, unchanged from the legacy InputValidator. */
    private static final String INITIAL_MESSAGE =
            "Le versement initial doit être compris entre 100,00 et 100 000,00 €."
            + " Utilisez au maximum deux décimales, sans séparateur de milliers.";
    private static final String MONTHLY_MESSAGE =
            "La mensualité doit être comprise entre 0,00 et 2 000,00 €."
            + " Utilisez au maximum deux décimales, sans séparateur de milliers.";
    private static final String YEARS_MESSAGE = "La durée doit être un nombre entier de 1 à 30 ans.";

    private static String body(String initial, String monthly, String years, String rate) {
        return String.format(
                "{\"initialAmount\":%s,\"monthlyAmount\":%s,\"durationYears\":%s,\"annualRate\":%s}",
                initial, monthly, years, rate);
    }

    // ---------------------------------------------------------------- nominal

    /**
     * The nominal case of the legacy journey (1000,00 initial, 100,00 monthly, 5 years): the
     * figures the legacy page rendered in {@code #capital-final} are now JSON fields.
     */
    @Test
    void nominalCaseReturnsTheProjectionAsJson() {
        given().contentType(ContentType.JSON)
                .body(body("1000.00", "100.00", "5", "0.040000"))
        .when().post("/v1/simulations")
        .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("initialAmount", equalTo("1000.00"))
                .body("monthlyAmount", equalTo("100.00"))
                .body("durationYears", equalTo(5))
                .body("contributed", equalTo("7000.00"))
                .body("finalCapital", notNullValue())
                .body("finalCapital", matchesPattern(MONEY));
    }

    /** The success media type is {@code application/json}, as declared by the frozen contract. */
    @Test
    void successResponseUsesTheJsonMediaType() {
        given().contentType(ContentType.JSON)
                .body(body("100.00", "0.00", "1", "0.000000"))
        .when().post("/v1/simulations")
        .then().statusCode(200)
                .header("Content-Type", startsWith("application/json"));
    }

    /** Every monetary field of the response carries scale 2, including a zero-rate projection. */
    @ParameterizedTest
    @CsvSource({
            "100.00,0.00,1,0.000000",
            "1000.00,100.00,5,0.040000",
            "100000.00,2000.00,30,0.040000"
    })
    void everyMonetaryFieldKeepsScaleTwo(String initial, String monthly, String years, String rate) {
        given().contentType(ContentType.JSON)
                .body(body(initial, monthly, years, rate))
        .when().post("/v1/simulations")
        .then().statusCode(200)
                .body("initialAmount", matchesPattern(MONEY))
                .body("monthlyAmount", matchesPattern(MONEY))
                .body("contributed", matchesPattern(MONEY))
                .body("finalCapital", matchesPattern(MONEY));
    }

    // ----------------------------------------------------------------- bounds

    /** Both bounds of each field are accepted: the contract limits are inclusive. */
    @ParameterizedTest
    @CsvSource({
            "100.00,0.00,1,0.000000",
            "100000.00,0.00,1,0.000000",
            "100.00,2000.00,1,0.000000",
            "100.00,0.00,30,0.000000",
            "100.00,0.00,1,1.000000"
    })
    void inclusiveBoundsAreAccepted(String initial, String monthly, String years, String rate) {
        given().contentType(ContentType.JSON)
                .body(body(initial, monthly, years, rate))
        .when().post("/v1/simulations")
        .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("finalCapital", matchesPattern(MONEY));
    }

    /** Just outside each bound the request is refused on its own wire property name. */
    @ParameterizedTest
    @CsvSource({
            "99.99,0.00,1,0.000000,initialAmount",
            "100000.01,0.00,1,0.000000,initialAmount",
            "100.00,-0.01,1,0.000000,monthlyAmount",
            "100.00,2000.01,1,0.000000,monthlyAmount",
            "100.00,0.00,0,0.000000,durationYears",
            "100.00,0.00,31,0.000000,durationYears",
            "100.00,0.00,1,1.000001,annualRate"
    })
    void valuesOutsideTheBoundsAreRefusedAsProblemJson(String initial, String monthly, String years,
            String rate, String field) {
        given().contentType(ContentType.JSON)
                .body(body(initial, monthly, years, rate))
        .when().post("/v1/simulations")
        .then().statusCode(400)
                .header("Content-Type", startsWith("application/problem+json"))
                .body("status", equalTo(400))
                .body("title", notNullValue())
                .body("errors[0].field", equalTo(field));
    }

    // ---------------------------------------------------- problem+json errors

    /** A third decimal on an amount is refused: the wire scale is two. */
    @Test
    void thirdDecimalIsRefusedAsProblemJson() {
        given().contentType(ContentType.JSON)
                .body(body("100.001", "0.00", "1", "0.000000"))
        .when().post("/v1/simulations")
        .then().statusCode(400)
                .header("Content-Type", startsWith("application/problem+json"))
                .body("errors[0].field", equalTo("initialAmount"));
    }

    /** A missing required field is refused on that field, not with a 500. */
    @Test
    void missingFieldIsRefusedAsProblemJson() {
        given().contentType(ContentType.JSON)
                .body("{\"monthlyAmount\":0.00,\"durationYears\":1,\"annualRate\":0.000000}")
        .when().post("/v1/simulations")
        .then().statusCode(400)
                .header("Content-Type", startsWith("application/problem+json"))
                .body("status", equalTo(400))
                .body("errors[0].field", equalTo("initialAmount"));
    }

    /**
     * The error document is {@code application/problem+json}, keyed on the wire field names, and
     * carries the French message the legacy page rendered inside its {@code .error} list item.
     */
    @Test
    void errorBodiesAreProblemJsonWithWireFieldNames() {
        given().contentType(ContentType.JSON)
                .body(body("-1.00", "1.001", "31", "0.000000"))
        .when().post("/v1/simulations")
        .then().statusCode(400)
                .header("Content-Type", startsWith("application/problem+json"))
                .body("status", equalTo(400))
                .body("title", notNullValue())
                .body("errors.field", hasItems("initialAmount", "monthlyAmount", "durationYears"))
                .body("errors.find { it.field == 'initialAmount' }.message", equalTo(INITIAL_MESSAGE))
                .body("errors.find { it.field == 'monthlyAmount' }.message", equalTo(MONTHLY_MESSAGE))
                .body("errors.find { it.field == 'durationYears' }.message", equalTo(YEARS_MESSAGE));
    }

    // -------------------------------------------------------- contracted set

    /**
     * The response carries exactly the five fields of {@code SimulationResult}: no {@code gains}
     * (the consumer derives it from {@code finalCapital - contributed}) and none of the request
     * attributes that drove the legacy JSP rendering ({@code step}, {@code pageTitle}), which were
     * reallocated to {@code souscription-web}.
     */
    @Test
    void responseCarriesExactlyTheContractedFields() {
        given().contentType(ContentType.JSON)
                .body(body("1000.00", "100.00", "5", "0.040000"))
        .when().post("/v1/simulations")
        .then().statusCode(200)
                .body("$", aMapWithSize(5))
                .body("$", hasKey("initialAmount"))
                .body("$", hasKey("monthlyAmount"))
                .body("$", hasKey("durationYears"))
                .body("$", hasKey("contributed"))
                .body("$", hasKey("finalCapital"))
                .body("$", not(hasKey("gains")))
                .body("$", not(hasKey("step")))
                .body("$", not(hasKey("pageTitle")));
    }

    /** A malformed JSON document is refused as problem+json, never as an HTML error page. */
    @Test
    void malformedJsonIsRefusedAsProblemJson() {
        given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":")
        .when().post("/v1/simulations")
        .then().statusCode(400)
                .header("Content-Type", startsWith("application/problem+json"))
                .body("status", equalTo(400))
                .body("title", notNullValue());
    }
}
