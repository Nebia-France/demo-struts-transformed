package fr.demo.souscription.application;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;

import fr.demo.souscription.domain.Product;
import fr.demo.souscription.domain.SubscriptionDraftEntity;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.DraftRepository;
import fr.demo.souscription.persistence.PostgresDevServicesProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * W7-T06 : la confirmation ne fait jamais confiance a ce qui a ete valide plus tot.
 *
 * <p>Le brouillon est ecrit <b>directement par le repository</b>, en contournant entierement la
 * couche REST et donc le {@code @Valid} de {@code PUT /v1/drafts/{draftId}/subscriber}. Si la
 * confirmation se reposait sur la validation de la saisie, ce brouillon invalide serait persiste
 * en souscription. La revalidation serveur de {@code ConfirmationService.confirmIdempotent} doit
 * au contraire le refuser en <b>400 application/problem+json</b>, avec la cle de message et le
 * code stable de {@code ERROR_VOCABULARY.md} — corps identique a celui du refus de saisie.
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class ConfirmRevalidationTest {

    private static final Product PRODUCT =
            new Product("PRUDENT", "Horizon Prudent", "Prudent", new BigDecimal("0.020000"));

    @Inject
    DraftRepository drafts;

    @Inject
    Clock clock;

    /**
     * Brouillon complet au sens du parcours (souscripteur et simulation renseignes, donc aucun
     * 409 {@code PRECONDITION_SEQUENCE}) mais invalide au sens des contraintes : seule la
     * revalidation peut le refuser.
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

    private void expectRejection(SubscriptionDraftEntity draft, String field, String message) {
        given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + draft.confirmationKey + "\"}")
                .when().post("/v1/drafts/" + draft.draftId + "/confirmation")
                .then().statusCode(400)
                .contentType(containsString("application/problem+json"))
                .body("errors.field", hasItem(field))
                .body("errors.find { it.field == '" + field + "' }.message", equalTo(message));
    }

    @Test
    void anInvalidEmailWrittenBehindTheRestLayerIsStillRejectedAtConfirmation() {
        // 254 caracteres, longueur maximale admise, mais sans arobase : seule l'expression
        // reguliere du source tranche, et elle n'a jamais traverse la couche REST.
        String email = "a".repeat(254);
        expectRejection(persistedDraft(email, LocalDate.of(1985, 4, 12), new BigDecimal("1000.00")),
                "email", "subscriber.email.invalid");
    }

    @Test
    void aMinorSubscriberWrittenBehindTheRestLayerIsStillRejectedAtConfirmation() {
        LocalDate minor = LocalDate.now(clock).minusYears(17);
        expectRejection(persistedDraft("claire.martin@example.com", minor,
                new BigDecimal("1000.00")), "birthDate", "subscriber.birthDate.minor");
    }

    @Test
    void anOutOfRangeAmountWrittenBehindTheRestLayerIsStillRejectedAtConfirmation() {
        expectRejection(persistedDraft("claire.martin@example.com", LocalDate.of(1985, 4, 12),
                new BigDecimal("1.00")), "initialAmount", "simulation.initialAmount.range");
    }
}
