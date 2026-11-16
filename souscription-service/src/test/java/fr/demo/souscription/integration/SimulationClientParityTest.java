package fr.demo.souscription.integration;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.SubscriptionRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parite du plan 28 : le recalcul autoritaire de {@code SubscriptionService.confirm}
 * (BASELINE §4.3) devenu un appel distant, toujours hors transaction.
 *
 * <p>Trois obligations, et une seule facon de les prouver — une ligne reellement persistee :
 * <ul>
 *   <li>le {@code final_capital} persiste est celui que <b>retourne le fournisseur</b>, jamais la
 *       projection stockee dans le brouillon ni une valeur du corps de la requete ; le bouchon
 *       renvoie une valeur volontairement invraisemblable ({@code 99999.99}), car une valeur
 *       plausible serait aussi celle qu'un recalcul local produirait et le test passerait contre
 *       le defaut meme qu'il doit attraper ;</li>
 *   <li>une simulation indisponible repond un <b>503 rejouable</b> avec <b>zero</b> ligne
 *       ecrite — confirmer sur la valeur du brouillon vendrait une projection non verifiee ;</li>
 *   <li>l'ecriture est tentee <b>exactement une fois</b> : un reessai autour d'une ecriture non
 *       idempotente produit la meme 201 qu'une tentative unique, seul le journal du bouchon le
 *       revele.</li>
 * </ul>
 *
 * <p>Les criteres qui ne demandent pas de base (valeurs de resilience, taux du catalogue sur le
 * fil, echelles, absence de {@code @Retry}/{@code @Fallback}, absence de transaction pendant
 * l'appel) sont dans {@link SimulationClientParityOfflineTest} et restent verifies lorsque aucun
 * daemon de conteneurs n'est disponible.
 */
@QuarkusTest
@TestProfile(SimulationParityDatabaseProfile.class)
@RequiresContainerRuntime
class SimulationClientParityTest {

    private static SimulationStubServer simulation;
    private static CatalogueStubServer catalogue;

    @Inject
    SubscriptionRepository subscriptions;

    @BeforeAll
    static void startStubs() throws IOException {
        simulation = new SimulationStubServer();
        catalogue = new CatalogueStubServer();
    }

    @AfterAll
    static void stopStubs() {
        simulation.close();
        catalogue.close();
    }

    @BeforeEach
    void resetStubs() {
        simulation.reset();
        simulation.serveComputation();
        catalogue.reset();
        catalogue.serveProduct();
        TransactionProbeFilter.reset();
    }

    /** Etape 2 : la projection persistee est celle du fournisseur. */
    @Test
    void persistedProjectionIsTheRemoteValue() {
        String draftId = completeDraft();
        String key = currentKey(draftId);
        simulation.serveFixedFinalCapital("99999.99");

        String reference = given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(201).extract().path("reference");

        String persisted = given().when().get("/v1/subscriptions/" + reference)
                .then().statusCode(200).extract().path("simulation.finalCapital");
        assertEquals(0, new BigDecimal("99999.99").compareTo(new BigDecimal(persisted)),
                "le final_capital persiste doit etre la valeur calculee par le serveur distant, "
                + "ni celle du brouillon ni celle de la requete (BASELINE §4.3)");
    }

    /** Etape 4 : une panne de simulation repond 503 rejouable et n'ecrit aucune ligne. */
    @Test
    void outageYieldsRetryable503WithNoPartialSubscription() {
        String draftId = completeDraft();
        String key = currentKey(draftId);
        simulation.serveStatus(500);
        long before = rows();

        String body = given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(503).extract().asString();

        assertTrue(body.contains("_RETRYABLE"),
                "seul un 503 rejouable conserve la promesse du source : aucune confirmation "
                + "emise, saisie conservee, requete rejouable ; corps=" + body);
        assertEquals(before, rows(),
                "aucune ligne ne doit etre ecrite : confirmer sur la projection perimee du "
                + "brouillon vendrait une valeur non verifiee");
    }

    /** Etape 5 : une confirmation = un appel au fournisseur et une seule ligne. */
    @Test
    void confirmationWriteIsAttemptedExactlyOnce() {
        String draftId = completeDraft();
        String key = currentKey(draftId);
        simulation.reset();

        given().contentType(ContentType.JSON).body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation").then().statusCode(201);

        assertEquals(1, simulation.requests(),
                "plus d'un appel signale un reessai autour du chemin d'ecriture, interdit sur "
                + "une ecriture non idempotente");
        assertEquals(1, rowsForKey(key), "exactement une ligne de souscription");
    }

    /** Etape 7 : aucune transaction n'est active pendant l'appel distant de confirmation. */
    @Test
    void noTransactionActiveDuringRemoteCall() {
        String draftId = completeDraft();
        String key = currentKey(draftId);
        TransactionProbeFilter.reset();

        given().contentType(ContentType.JSON).body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation").then().statusCode(201);

        assertEquals(1, TransactionProbeFilter.observed(),
                "la sonde doit avoir observe la requete sortante, sinon le test ne prouve rien");
        assertFalse(TransactionProbeFilter.transactionSeen(),
                "une transaction englobant l'appel retiendrait une connexion du pool pendant "
                + "plus de 6 s (3 s de timeout plus un reessai)");
    }

    /** Etape 10 : le 400 du fournisseur est rendu 400, avec ses verdicts par champ. */
    @Test
    void providerValidationFailureIsRelayedAsClientError() {
        String draftId = startDraft();
        simulation.reset();
        simulation.serveRejectionWithFieldErrors();

        String body = given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put("/v1/drafts/" + draftId + "/simulation")
                .then().statusCode(400).extract().asString();

        assertTrue(body.contains("durationYears") && body.contains("DURATION_YEARS_RANGE"),
                "les verdicts par champ du fournisseur doivent etre relayes : ce sont les erreurs "
                + "que le formulaire source rendait a cote du champ ; corps=" + body);
        assertEquals(1, simulation.requests(), "un 4xx n'est jamais reessaye");
    }

    private long rows() {
        return QuarkusTransaction.requiringNew().call(subscriptions::count);
    }

    private long rowsForKey(String key) {
        return QuarkusTransaction.requiringNew()
                .call(() -> subscriptions.find("confirmationKey", UUID.fromString(key)).count());
    }

    private String startDraft() {
        return given().contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts").then().statusCode(201).extract().path("draftId");
    }

    private String completeDraft() {
        String draftId = startDraft();
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Dupont\","
                        + "\"email\":\"claire.dupont@example.org\",\"birthDate\":\"1990-05-04\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber").then().statusCode(200);
        given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put("/v1/drafts/" + draftId + "/simulation").then().statusCode(200);
        return draftId;
    }

    private String currentKey(String draftId) {
        return given().when().get("/v1/drafts/" + draftId)
                .then().statusCode(200).extract().path("confirmationKey");
    }
}
