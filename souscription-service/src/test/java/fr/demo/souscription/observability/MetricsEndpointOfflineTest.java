package fr.demo.souscription.observability;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Variante hors conteneur de {@link MetricsEndpointTest} (W5-T03) : verifie l'exposition du
 * point {@code /q/metrics}, les familles par defaut qui n'exigent aucune base, et la
 * pre-declaration des cinq compteurs metier a zero. Les valeurs non nulles du parcours complet
 * sont mesurees par {@link MetricsEndpointTest}, qui exige Dev Services.
 *
 * <p>Le refus de validation est verifie ici : il est tranche avant tout acces a la base, le
 * compteur {@code horizon.validation.rejected} est donc observable sans datasource actif.
 */
@EnabledUnlessPropertyFalse(named = "quarkus.micrometer.enabled",
        reason = "chemin de retour arriere de la vague 5 : sans registre de metriques il n'y a aucune serie a verifier ; le comportement metier est verifie par les autres tests, qui passent avec l'instrumentation desarmee")
@QuarkusTest
class MetricsEndpointOfflineTest {

    @Test
    void metricsEndpointIsServedAndPreRegistersTheFiveBusinessCounters() {
        String body = given().when().get("/q/metrics")
                .then().statusCode(200).extract().asString();

        for (String family : new String[] {"http_server_requests_seconds", "jvm_memory_"}) {
            assertTrue(body.contains(family),
                    "/q/metrics must expose the default family " + family);
        }
        for (String counter : new String[] {"horizon_drafts_created_total",
                "horizon_confirmations_succeeded_total", "horizon_confirmations_duplicate_total",
                "horizon_validation_rejected_total", "horizon_draft_purged_total"}) {
            assertTrue(body.contains(counter),
                    "/q/metrics must expose " + counter + " even at zero: a missing series is "
                    + "indistinguable from a broken scrape and makes the dashboards show no data");
        }
    }

    @Test
    void aRejectedRequestIncrementsTheValidationCounter() {
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"\",\"lastName\":\"\",\"email\":\"nope\","
                        + "\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + UUID.randomUUID() + "/subscriber")
                .then().statusCode(400);

        String body = given().when().get("/q/metrics")
                .then().statusCode(200).extract().asString();
        assertTrue(body.lines().anyMatch(line -> line.startsWith("horizon_validation_rejected_total")
                        && Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)) > 0d),
                "horizon_validation_rejected_total must be non-zero after a rejected request: the "
                + "counter is incremented by ConstraintViolationMapper, on every rejection path");
    }
}
