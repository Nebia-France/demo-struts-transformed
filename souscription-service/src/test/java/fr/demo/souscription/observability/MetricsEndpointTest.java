package fr.demo.souscription.observability;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Preuve d'exposition des metriques (W5-T03). La source n'exposait aucune metrique : ce test
 * mesure la cible NFG-015 — le jeu Prometheus par defaut plus les cinq compteurs metier — en
 * jouant un parcours complet (ouverture du brouillon, souscripteur, simulation, confirmation,
 * rejeu) puis un refus de validation, avant de gratter {@code /q/metrics}.
 *
 * <p>Le parcours exige une base reelle (Dev Services PostgreSQL) et est ignore sans daemon de
 * conteneurs, comme les autres tests de persistance. La partie qui n'a besoin d'aucune base
 * (familles par defaut et compteur de rejets de validation) est verifiee hors conteneur par
 * {@link MetricsEndpointOfflineTest}.
 */
@EnabledUnlessPropertyFalse(named = "quarkus.micrometer.enabled",
        reason = "chemin de retour arriere de la vague 5 : sans registre de metriques il n'y a aucune serie a verifier ; le comportement metier est verifie par les autres tests, qui passent avec l'instrumentation desarmee")
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class MetricsEndpointTest {

    @Inject
    MeterRegistry registry;

    private String scrape() {
        return given().when().get("/q/metrics").then().statusCode(200).extract().asString();
    }

    @Test
    void metricsEndpointExposesTheDefaultFamiliesAndTheFiveBusinessCounters() {
        double draftsBefore = count(BusinessMetrics.DRAFTS_CREATED);
        double confirmedBefore = count(BusinessMetrics.CONFIRMATIONS);
        double duplicatesBefore = count(BusinessMetrics.DUPLICATES);
        double rejectedBefore = count(BusinessMetrics.VALIDATION_REJECTED);

        String draftId = given().contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts")
                .then().statusCode(201)
                .extract().path("draftId");
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(200);
        String key = given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put("/v1/drafts/" + draftId + "/simulation")
                .then().statusCode(200)
                .extract().path("confirmationKey");
        given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(201);
        // Rejeu de la meme cle : court-circuite par l'idempotence, compte comme doublon.
        given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(200);
        // Refus de validation : prenom vide.
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(400);

        assertEquals(1.0d, count(BusinessMetrics.DRAFTS_CREATED) - draftsBefore, 0.0d,
                BusinessMetrics.DRAFTS_CREATED + " must count exactly one created draft");
        assertEquals(1.0d, count(BusinessMetrics.CONFIRMATIONS) - confirmedBefore, 0.0d,
                BusinessMetrics.CONFIRMATIONS + " must count the confirmation once, not the replay");
        assertEquals(1.0d, count(BusinessMetrics.DUPLICATES) - duplicatesBefore, 0.0d,
                BusinessMetrics.DUPLICATES + " must count the replay short-circuited by the "
                + "idempotency key");
        assertEquals(1.0d, count(BusinessMetrics.VALIDATION_REJECTED) - rejectedBefore, 0.0d,
                BusinessMetrics.VALIDATION_REJECTED + " must be incremented once per rejected "
                + "request, by the mapper and not by the resources");

        String body = scrape();
        for (String family : new String[] {"http_server_requests_seconds", "agroal_active_count",
                "jvm_memory_"}) {
            assertTrue(body.contains(family),
                    "/q/metrics must expose the default family " + family);
        }
        for (String counter : new String[] {"horizon_drafts_created_total",
                "horizon_confirmations_succeeded_total", "horizon_confirmations_duplicate_total",
                "horizon_validation_rejected_total", "horizon_draft_purged_total"}) {
            assertTrue(body.contains(counter),
                    "/q/metrics must expose the business counter " + counter
                    + " (dots become underscores and a _total suffix is added by the registry)");
        }
    }

    /**
     * Somme des series d'un compteur, etiquettes comprises : {@code horizon.confirmations.duplicate}
     * est etiquete par chemin ({@code read} / {@code conflict}), une lecture d'une seule serie
     * passerait a cote de la moitie des rejeux.
     */
    private double count(String name) {
        return registry.find(name).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }
}
