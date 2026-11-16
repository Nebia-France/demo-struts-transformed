package fr.demo.souscription.api.validation;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.demo.souscription.domain.Product;
import fr.demo.souscription.domain.SubscriptionDraftEntity;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.DraftRepository;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Volet HTTP du controle de parite du plan 34 (etapes 4 a 6) : la confirmation revalide-t-elle
 * un brouillon rendu invalide <b>derriere</b> la couche REST, et avec le meme corps d'erreur ?
 *
 * <p>Le brouillon ne vit plus en session comme dans le source : il est persiste et survit aux
 * changements de regle, et tout processus ayant acces au repository peut l'ecrire. La garantie
 * explicite du source — {@code IllegalArgumentException("Souscripteur invalide")} a chaque
 * {@code subscriber(form)} — n'a donc de successeur que si la confirmation revalide elle-meme.
 *
 * <p>Separe de {@link FailClosedParityTest} a dessein : les controles de reflexion doivent rester
 * verifiables sans daemon de conteneurs, que Dev Services puisse demarrer PostgreSQL ou non.
 */
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class FailClosedParityDatabaseTest {

    private static final Product PRODUCT =
            new Product("PRUDENT", "Horizon Prudent", "Prudent", new BigDecimal("0.020000"));

    /**
     * Adresse sans domaine de premier niveau : refusee par l'expression reguliere de
     * {@code SubscriberInput}, mais assez courte pour tenir dans la colonne {@code varchar(254)}
     * du brouillon (une adresse trop longue ne peut pas etre persistee, la base la rejette).
     */
    private static final String INVALID_EMAIL = "claire.martin@example";

    @Inject
    DraftRepository drafts;

    @Inject
    Clock clock;

    /** Etape 4 : un brouillon invalide ecrit par le repository est refuse a la confirmation. */
    @Test
    void confirmationRevalidatesAPersistedInvalidDraft() {
        SubscriptionDraftEntity draft = persistedDraft(INVALID_EMAIL,
                LocalDate.of(1985, 4, 12), new BigDecimal("1000.00"));

        given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + draft.confirmationKey + "\"}")
                .when().post("/v1/drafts/" + draft.draftId + "/confirmation")
                .then().statusCode(400)
                .contentType(containsString("application/problem+json"))
                .body("errors.field", hasItem("email"))
                .body("errors.find { it.field == 'email' }.code", org.hamcrest.Matchers.equalTo(
                        "EMAIL_INVALID"));
    }

    /**
     * Etape 5 : une regle, une seule forme d'erreur. La meme adresse invalide detectee a
     * {@code PUT …/subscriber} et a la confirmation doit produire le meme statut, les memes cles
     * de champ et les memes codes — sinon la SPA doit traiter deux formes pour une regle.
     */
    @Test
    void revalidationProblemBodyIsIdenticalToTheRestLayer() {
        String restDraftId = startDraft();
        JsonPath fromRest = new JsonPath(given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"" + INVALID_EMAIL + "\","
                        + "\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + restDraftId + "/subscriber")
                .then().statusCode(400)
                .contentType(containsString("application/problem+json"))
                .extract().asString());

        SubscriptionDraftEntity persisted = persistedDraft(INVALID_EMAIL,
                LocalDate.of(1985, 4, 12), new BigDecimal("1000.00"));
        JsonPath fromConfirmation = new JsonPath(given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + persisted.confirmationKey + "\"}")
                .when().post("/v1/drafts/" + persisted.draftId + "/confirmation")
                .then().statusCode(400)
                .contentType(containsString("application/problem+json"))
                .extract().asString());

        assertEquals(fromRest.getInt("status"), fromConfirmation.getInt("status"),
                "le statut doit etre identique aux deux points de validation");
        assertEquals(fields(fromRest), fields(fromConfirmation),
                "les cles de champ doivent etre identiques aux deux points de validation");
        assertEquals(codes(fromRest), codes(fromConfirmation),
                "les codes stables doivent etre identiques aux deux points de validation");
        assertEquals(messages(fromRest), messages(fromConfirmation),
                "les cles de message doivent etre identiques aux deux points de validation");
    }

    /**
     * Etape 6 : les montants sont revalides eux aussi. Sans cela, la seule defense restante est
     * le CHECK de la base, qui remonte en 500 et non en 400.
     */
    @Test
    void confirmationRevalidatesAmounts() {
        SubscriptionDraftEntity draft = persistedDraft("claire.martin@example.com",
                LocalDate.of(1985, 4, 12), new BigDecimal("99.99"));

        given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + draft.confirmationKey + "\"}")
                .when().post("/v1/drafts/" + draft.draftId + "/confirmation")
                .then().statusCode(400)
                .contentType(containsString("application/problem+json"))
                .body("errors.field", hasItem("initialAmount"));
    }

    /**
     * Brouillon complet au sens du parcours — souscripteur et simulation renseignes, donc aucun
     * 409 {@code PRECONDITION_SEQUENCE} — mais invalide au sens des contraintes, et ecrit en
     * contournant entierement la couche REST.
     */
    private SubscriptionDraftEntity persistedDraft(String email, LocalDate birthDate,
                                                   BigDecimal initialAmount) {
        SubscriptionDraftEntity draft =
                SubscriptionDraftEntity.create(PRODUCT, clock.instant(), Duration.ofMinutes(20));
        draft.firstName = "Claire";
        draft.lastName = "Martin";
        draft.email = email;
        draft.birthDate = birthDate;
        draft.initialAmount = initialAmount;
        draft.monthlyAmount = new BigDecimal("100.00");
        draft.durationYears = 10;
        draft.contributed = new BigDecimal("13000.00");
        draft.finalCapital = new BigDecimal("14000.00");
        QuarkusTransaction.requiringNew().run(() -> drafts.persist(draft));
        return draft;
    }

    private String startDraft() {
        return given().contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts")
                .then().statusCode(201)
                .extract().path("draftId").toString();
    }

    private static List<String> fields(JsonPath body) {
        return body.getList("errors.field", String.class);
    }

    private static List<String> codes(JsonPath body) {
        return body.getList("errors.code", String.class);
    }

    private static List<String> messages(JsonPath body) {
        return body.getList("errors.message", String.class);
    }
}
