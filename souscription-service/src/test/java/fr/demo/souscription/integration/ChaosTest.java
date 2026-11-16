package fr.demo.souscription.integration;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.application.SimulationUnavailableException;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.SubscriptionRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.ServiceUnavailableException;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Test de chaos du chemin de confirmation (W6-T06).
 *
 * <p>La confirmation enchaine deux appels sortants — recherche produit au catalogue puis recalcul
 * autoritaire de la simulation — avant d'ouvrir la transaction. Ce test verifie, pour chacune des
 * quatre perturbations, les trois memes garanties :
 * <ol>
 *   <li>la reponse est un <b>503 rejouable</b> (ou un 201 quand la latence reste sous le
 *       timeout) ;</li>
 *   <li>la <b>liveness</b> reste {@code UP} : le processus est sain, c'est la dependance qui est
 *       en panne, et un redemarrage ne reparerait rien ;</li>
 *   <li><b>aucune souscription partielle</b> : le nombre de lignes de
 *       {@code subscriptions.subscriptions} est inchange.</li>
 * </ol>
 *
 * <p>Cas couverts : simulation arretee, catalogue arrete, latence injectee de 2,5 s (sous le
 * timeout de 3 s — l'appel doit reussir), latence injectee de 3,5 s (au-dela du timeout — echec
 * rejouable).
 *
 * <p>Arret d'un fournisseur = bouchon ferme, donc port en refus de connexion : c'est ce que voit
 * un client lorsqu'un Service du cluster n'a plus d'endpoint. Les valeurs mesurees sont reportees
 * dans {@code deploy/CHAOS_TEST_REPORT.md}.
 *
 * <p>Marque {@code @Tag("chaos")} : la meme classe peut etre executee contre un environnement
 * deploye en surchargeant {@code quarkus.rest-client.*.url} et l'adresse de base RestAssured.
 * Exige un stockage reel, donc un daemon de conteneurs ; ignoree a defaut, comme les autres tests
 * de parcours complet.
 */
@QuarkusTest
@TestProfile(ChaosProfile.class)
@RequiresContainerRuntime
@Tag("chaos")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChaosTest {

    @Inject
    SubscriptionRepository repository;

    /** Cas 1 : souscription-simulation-service arrete pendant la confirmation. */
    @Test
    @Order(1)
    void simulationArreteeRendUn503RejouableSansLigneCreee() throws IOException {
        try (CatalogueStubServer catalogue = new CatalogueStubServer();
                SimulationStubServer simulation = new SimulationStubServer()) {
            Draft draft = completeDraft();
            long before = subscriptionCount();

            simulation.close();
            long elapsed = confirmAndExpect(draft, 503, "urn:horizon:simulation-unavailable");

            assertLivenessUp();
            assertEquals(before, subscriptionCount(),
                    "une panne du recalcul ne doit creer aucune souscription partielle");
            report("simulation arretee", elapsed, 503);
        }
    }

    /** Cas 2 : souscription-catalogue-service arrete pendant la confirmation. */
    @Test
    @Order(2)
    void catalogueArreteRendUn503RejouableSansLigneCreee() throws IOException {
        try (SimulationStubServer simulation = new SimulationStubServer()) {
            Draft draft;
            try (CatalogueStubServer catalogue = new CatalogueStubServer()) {
                draft = completeDraft();
            }
            long before = subscriptionCount();

            long elapsed = confirmAndExpect(draft, 503, "urn:horizon:service-unavailable");

            assertLivenessUp();
            assertEquals(before, subscriptionCount(),
                    "un catalogue injoignable ne doit creer aucune souscription partielle : le "
                    + "taux annuel persiste est toujours celui du catalogue");
            report("catalogue arrete", elapsed, 503);
        }
    }

    /** Cas 3 : latence de 2,5 s, sous le timeout de 3 s — l'appel doit reussir. */
    @Test
    @Order(3)
    void uneLatenceSousLeTimeoutReussit() throws IOException {
        try (CatalogueStubServer catalogue = new CatalogueStubServer();
                SimulationStubServer simulation = new SimulationStubServer()) {
            Draft draft = completeDraft();
            long before = subscriptionCount();

            simulation.serveWithLatency(2500);
            long elapsed = confirmAndExpect(draft, 201, null);

            assertLivenessUp();
            assertEquals(before + 1, subscriptionCount(),
                    "une dependance lente mais non defaillante doit aboutir a une souscription "
                    + "complete, et a une seule");
            assertTrue(elapsed >= 2500,
                    "le test ne prouve rien si la latence n'a pas ete subie ; mesure="
                    + elapsed + "ms");
            report("latence 2500 ms", elapsed, 201);
        }
    }

    /** Cas 4 : latence de 3,5 s, au-dela du timeout de 3 s — echec rejouable. */
    @Test
    @Order(4)
    void uneLatenceAuDelaDuTimeoutRendUn503Rejouable() throws IOException {
        try (CatalogueStubServer catalogue = new CatalogueStubServer();
                SimulationStubServer simulation = new SimulationStubServer()) {
            Draft draft = completeDraft();
            long before = subscriptionCount();

            simulation.serveWithLatency(3500);
            long elapsed = confirmAndExpect(draft, 503, "urn:horizon:simulation-unavailable");

            assertLivenessUp();
            assertEquals(before, subscriptionCount(),
                    "un depassement de delai ne doit creer aucune souscription partielle");
            report("latence 3500 ms", elapsed, 503);
        }
    }

    private record Draft(String draftId, String confirmationKey) {
    }

    /**
     * Confirme le brouillon et retourne la duree mesuree. Le {@code type} attendu distingue la
     * cause rendue a la SPA : {@code urn:horizon:simulation-unavailable} pour le recalcul,
     * {@code urn:horizon:service-unavailable} pour le catalogue. Les deux sont rejouables.
     */
    private long confirmAndExpect(Draft draft, int status, String problemType) {
        long start = System.nanoTime();
        var response = given().contentType(ContentType.JSON)
                .body("{\"confirmationKey\":\"" + draft.confirmationKey() + "\"}")
                .when().post("/v1/drafts/" + draft.draftId() + "/confirmation")
                .then().statusCode(status);
        if (problemType != null) {
            response.contentType("application/problem+json").body("type", Matchers.is(problemType));
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    private void assertLivenessUp() {
        RestAssured.when().get("/q/health/live")
                .then().statusCode(200).body("status", Matchers.is("UP"));
    }

    private long subscriptionCount() {
        return QuarkusTransaction.requiringNew().call(() -> repository.count());
    }

    private Draft completeDraft() {
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
        return new Draft(draftId, key);
    }

    /** Les valeurs mesurees sont reportees dans {@code deploy/CHAOS_TEST_REPORT.md}. */
    private static void report(String scenario, long elapsedMillis, int status) {
        System.out.printf("[chaos] %s -> status=%d duree=%dms%n", scenario, status, elapsedMillis);
    }
}

/**
 * Partie du test de chaos qui n'exige pas de stockage (W6-T06).
 *
 * <p>Les quatre memes perturbations sont appliquees, mais mesurees sur les <b>appels sortants
 * seuls</b> : {@link CatalogueLookup} et {@link SimulationLookup} sont les deux legs reseau du
 * chemin de confirmation, et ce sont eux qui decident du 503 rejouable. L'assertion
 * « aucune souscription partielle » n'a pas de sens ici — aucun datasource n'est arme, aucune
 * ligne ne peut etre ecrite — et reste portee par {@link ChaosTest}, qui exige une base reelle.
 *
 * <p>Interet : les durees et les verdicts des quatre cas sont <b>mesures</b> meme sans daemon de
 * conteneurs, ce qui est la situation de la pipeline hors CI Docker. Les valeurs reportees dans
 * {@code deploy/CHAOS_TEST_REPORT.md} proviennent de cette classe.
 */
@QuarkusTest
@TestProfile(ChaosProfile.Offline.class)
@Tag("chaos")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChaosOfflineTest {

    private static final SimulationComputeRequest STANDARD =
            new SimulationComputeRequest("1000.00", "100.00", 10, "0.020000");

    @Inject
    SimulationLookup simulations;

    @Inject
    CatalogueLookup catalogue;

    /** Cas 1 : souscription-simulation-service arrete — aucun bouchon n'ecoute le port. */
    @Test
    @Order(1)
    void simulationArreteeEstUn503Rejouable() {
        long start = System.nanoTime();
        SimulationUnavailableException outage = assertThrows(SimulationUnavailableException.class,
                () -> simulations.compute(STANDARD));
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        assertEquals(503, outage.getResponse().getStatus(),
                "un fournisseur arrete est un 503 rejouable, jamais un 500");
        assertLivenessUp();
        report("simulation arretee", elapsed, 503);
    }

    /** Cas 2 : souscription-catalogue-service arrete. */
    @Test
    @Order(2)
    void catalogueArreteEstUn503Rejouable() {
        long start = System.nanoTime();
        ServiceUnavailableException outage = assertThrows(ServiceUnavailableException.class,
                () -> catalogue.findProduct("PRUDENT"));
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        assertEquals(503, outage.getResponse().getStatus(),
                "un catalogue arrete est un 503 rejouable, et aucun taux de repli n'est servi");
        assertLivenessUp();
        report("catalogue arrete", elapsed, 503);
    }

    /** Cas 3 : latence de 2,5 s, sous le timeout de 3 s — l'appel doit reussir. */
    @Test
    @Order(3)
    void uneLatenceSousLeTimeoutReussit() throws IOException {
        try (SimulationStubServer simulation = new SimulationStubServer()) {
            simulation.serveWithLatency(2500);

            long start = System.nanoTime();
            String finalCapital = simulations.compute(STANDARD).finalCapital();
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            assertEquals(SimulationStubServer.expectedFinalCapital("1000.00", "100.00", 10,
                    "0.020000"), finalCapital,
                    "une dependance lente mais non defaillante doit rendre sa valeur, non un repli");
            assertTrue(elapsed >= 2500,
                    "le test ne prouve rien si la latence n'a pas ete subie ; mesure="
                    + elapsed + "ms");
            assertTrue(elapsed < 3000,
                    "2,5 s doit rester sous le timeout de 3 s, sans reessai ; mesure="
                    + elapsed + "ms");
            assertLivenessUp();
            report("latence 2500 ms", elapsed, 200);
        }
    }

    /** Cas 4 : latence de 3,5 s, au-dela du timeout de 3 s — echec rejouable. */
    @Test
    @Order(4)
    void uneLatenceAuDelaDuTimeoutEstUn503Rejouable() throws IOException {
        try (SimulationStubServer simulation = new SimulationStubServer()) {
            simulation.serveWithLatency(3500);

            long start = System.nanoTime();
            SimulationUnavailableException expired =
                    assertThrows(SimulationUnavailableException.class,
                            () -> simulations.compute(STANDARD));
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            assertEquals(503, expired.getResponse().getStatus());
            assertTrue(elapsed >= 3000,
                    "le timeout de 3 s doit avoir ete atteint ; mesure=" + elapsed + "ms");
            assertLivenessUp();
            report("latence 3500 ms", elapsed, 503);
        }
    }

    private static void assertLivenessUp() {
        RestAssured.when().get("/q/health/live")
                .then().statusCode(200).body("status", Matchers.is("UP"));
    }

    private static void report(String scenario, long elapsedMillis, int status) {
        System.out.printf("[chaos-offline] %s -> status=%d duree=%dms%n", scenario, status,
                elapsedMillis);
    }
}
