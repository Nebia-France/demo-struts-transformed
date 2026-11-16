package fr.demo.souscription.contract;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import fr.demo.souscription.api.dto.SimulationResult;
import fr.demo.souscription.domain.SubscriptionEntity;
import fr.demo.souscription.integration.SimulationComputeRequest;
import fr.demo.souscription.integration.SimulationLookup;
import fr.demo.souscription.persistence.SubscriptionRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;

/**
 * Parcours complet contre les services soeurs <b>reels</b> deployes, sans bouchon (plan 42,
 * etape 4) : demarrage, souscripteur, simulation, confirmation, puis verification que le
 * {@code final_capital} persiste est exactement la valeur que souscription-simulation-service
 * retourne pour les memes parametres et le taux servi par souscription-catalogue-service.
 *
 * <p>Etiquete {@code real-siblings} et conditionne a {@code HORIZON_E2E_CATALOGUE_URL} et
 * {@code HORIZON_E2E_SIMULATION_URL} : sans services soeurs joignables, JUnit le rapporte ignore
 * avec sa condition, ce n'est jamais une desactivation inconditionnelle. Exclusion explicite
 * possible par {@code -DexcludedGroups=real-siblings}.
 */
@QuarkusTest
@TestProfile(RealSiblingsProfile.class)
@Tag("real-siblings")
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = RealSiblingsProfile.CATALOGUE_URL, matches = ".+"),
        @EnabledIfEnvironmentVariable(named = RealSiblingsProfile.SIMULATION_URL, matches = ".+")})
class RealSiblingsJourneyTest {

    @Inject
    SimulationLookup simulations;

    @Inject
    SubscriptionRepository repository;

    @Test
    void theFullJourneyPersistsTheRemoteFinalCapital() {
        JsonNode draft = ContractDocuments.parse(given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts")
                .then().statusCode(201).extract().asString());
        String draftId = draft.get("draftId").asText();
        String annualRate = draft.path("product").path("annualRate").asText();

        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(200);

        JsonNode simulated = ContractDocuments.parse(given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put("/v1/drafts/" + draftId + "/simulation")
                .then().statusCode(200).extract().asString());
        UUID confirmationKey = UUID.fromString(simulated.get("confirmationKey").asText());

        int status = given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + confirmationKey + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().extract().statusCode();
        assertEquals(true, List.of(200, 201).contains(status), "confirmation : " + status);

        // Valeur de reference demandee directement au fournisseur reel, hors parcours.
        SimulationResult remote = simulations.compute(
                new SimulationComputeRequest("1000.00", "100.00", 10, annualRate));
        SubscriptionEntity stored = QuarkusTransaction.requiringNew()
                .call(() -> repository.findByConfirmationKey(confirmationKey).orElseThrow());

        assertEquals(remote.finalCapital(), stored.finalCapital.toPlainString(),
                "final_capital persiste = valeur du service de simulation reel");
        assertEquals(remote.contributed(), stored.contributed.toPlainString());
        assertEquals(annualRate, stored.annualRate.toPlainString(),
                "le taux persiste est celui du catalogue reel");
    }
}
