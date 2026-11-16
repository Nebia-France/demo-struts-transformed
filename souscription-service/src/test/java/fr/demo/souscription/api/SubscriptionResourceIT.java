package fr.demo.souscription.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.integration.CatalogueStubServer;
import fr.demo.souscription.integration.SimulationStubServer;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresDevServicesProfile;
import fr.demo.souscription.web.CsrfDefaultsCallback;
import fr.demo.souscription.web.CsrfDefenceFilter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * W8-T02 : test de bout en bout JSON du parcours de souscription, une methode par ligne de
 * {@code src/main/resources/STRUTS_REST_COVERAGE.md}.
 *
 * <p>Reecriture de {@code src/test/java/fr/demo/souscription/web/SubscriptionIT.java} (source,
 * conserve tel quel) : celui-ci pilotait le parcours en hypertexte, extrayait le jeton anti-CSRF
 * et les champs du formulaire par selecteurs de document et affirmait sur le rendu. Le rendu
 * appartient desormais a {@code souscription-web} : ce test est purement JSON, sans aucune
 * assertion ni dependance de rendu. Le jeton anti-CSRF n'est plus lu dans un formulaire rendu mais
 * pose explicitement en paire en-tete / cookie, comme le fait la passerelle.
 *
 * <p>Les deux dependances distantes, {@link fr.demo.souscription.integration.CatalogueClient} et
 * {@link fr.demo.souscription.integration.SimulationClient}, sont bouchonnees par les serveurs de
 * contrat geles {@link CatalogueStubServer} et {@link SimulationStubServer}, qui ecoutent les
 * ports vises par {@code %test.quarkus.rest-client.*.url} : le test est deterministe, aucun
 * deploiement voisin n'est sollicite. Le parcours contre les services reels appartient au plan 42.
 *
 * <p>Correspondance avec {@code STRUTS_REST_COVERAGE.md} (une ligne au moins par methode) :
 * <ul>
 *   <li>{@code /commencer} POST : {@link #parcoursCompletJusquaLaConfirmation} (201),
 *       {@link #startDraftRepond404SurUnCodeProduitInconnu} (404),
 *       {@link #startDraftRepond400SurUnCorpsInvalide} (400).</li>
 *   <li>{@code /souscripteur}, {@code /simulation}, {@code /recapitulatif} GET :
 *       {@link #getDraftRendLEtatDeChaqueEtape} (200),
 *       {@link #getDraftRepond404SurUnBrouillonInconnu} (404, inconnu ou expire).</li>
 *   <li>{@code /souscripteur} POST : parcours complet (200),
 *       {@link #putSubscriberRepond400SurUneEntreeInvalide} (400),
 *       {@link #putSubscriberRepond409SurUneMiseAJourConcurrente} (409),
 *       {@link #putSubscriberRepond404SurUnBrouillonInconnu} (404).</li>
 *   <li>{@code /simulation} POST : parcours complet (200),
 *       {@link #putSimulationRepond400SurDesMontantsInvalides} (400),
 *       {@link #putSimulationRepond409AvantLaSaisieDuSouscripteur} (409),
 *       {@link #putSimulationRepond404SurUnBrouillonInconnu} (404).</li>
 *   <li>{@code /confirmer} POST : {@link #confirmDraftRepond201PuisRejoue200AvecLaMemeReference}
 *       (201 puis 200 au rejeu), {@link #confirmDraftRepond409SurUneCleDivergente} (409),
 *       {@link #confirmDraftRepond409SurUnParcoursIncomplet} (409),
 *       {@link #confirmDraftRepond404SurUnBrouillonInconnu} (404).</li>
 *   <li>{@code /confirmation} GET : parcours complet (200),
 *       {@link #getSubscriptionRepond404SurUneReferenceInconnue} (404).</li>
 *   <li>Lignes « 405 natif », ligne « defaut » (404 natif), {@code /catalogue} et {@code /produit}
 *       : {@code SubscriptionResourceProtocolIT}, en bas de ce fichier, qui porte aussi le refus
 *       en 403 des ecritures sans paire double-submit valide.</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class SubscriptionResourceIT {

    private static CatalogueStubServer catalogue;

    private static SimulationStubServer simulation;

    @BeforeAll
    static void startStubs() throws IOException {
        catalogue = new CatalogueStubServer();
        simulation = new SimulationStubServer();
        catalogue.serveProduct();
        simulation.serveComputation();
    }

    @AfterAll
    static void stopStubs() {
        if (catalogue != null) {
            catalogue.close();
        }
        if (simulation != null) {
            simulation.close();
        }
    }

    static final String SUBSCRIBER = "{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
            + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}";

    static final String SIMULATION = "{\"initialAmount\":\"1000.00\","
            + "\"monthlyAmount\":\"100.00\",\"durationYears\":10}";

    /** Les trois en-tetes de securite du plan 32, avec leur valeur exacte. */
    static final Map<String, String> SECURITY_HEADERS = Map.of(
            "Cache-Control", "no-store",
            "X-Content-Type-Options", "nosniff",
            "Referrer-Policy", "no-referrer");

    /**
     * Paire double-submit posee explicitement : le jeton n'est plus extrait d'un champ cache d'un
     * formulaire rendu, il est emis en en-tete et repete dans le cookie transmis par la
     * passerelle.
     */
    static RequestSpecification csrf() {
        String token = "jeton-e2e-" + UUID.randomUUID();
        return given()
                .header(CsrfDefenceFilter.TOKEN_HEADER, token)
                .cookie(CsrfDefenceFilter.TOKEN_COOKIE, token);
    }

    static void assertSecurityHeaders(Response response, String operation) {
        SECURITY_HEADERS.forEach((name, value) -> assertEquals(value, response.getHeader(name),
                "l'en-tete " + name + " doit valoir " + value + " sur " + operation
                        + " (statut " + response.statusCode() + ")"));
    }

    private static Response expect(String operation, int status, Response response) {
        assertEquals(status, response.statusCode(),
                operation + " doit repondre " + status + " ; corps=" + response.asString());
        assertSecurityHeaders(response, operation);
        return response;
    }

    private static Response startDraft(String productCode) {
        return csrf().contentType(ContentType.JSON)
                .body("{\"productCode\":\"" + productCode + "\"}")
                .when().post("/v1/drafts");
    }

    private String startedDraftId() {
        return expect("POST /v1/drafts", 201, startDraft("PRUDENT")).path("draftId");
    }

    private String completedDraftKey(String draftId) {
        expect("PUT /v1/drafts/{draftId}/subscriber", 200,
                csrf().contentType(ContentType.JSON).body(SUBSCRIBER)
                        .when().put("/v1/drafts/" + draftId + "/subscriber"));
        return expect("PUT /v1/drafts/{draftId}/simulation", 200,
                csrf().contentType(ContentType.JSON).body(SIMULATION)
                        .when().put("/v1/drafts/" + draftId + "/simulation"))
                .path("confirmationKey");
    }

    // --- /commencer POST, /souscripteur POST, /simulation POST, /recapitulatif GET,
    // --- /confirmer POST, /confirmation GET : le parcours complet en une methode.

    /**
     * Parcours complet jusqu'a la confirmation, en JSON. Affirme aussi le format de fil gele :
     * montants et taux en chaines d'echelle preservee, date de naissance ISO, horodatage ISO.
     */
    @Test
    void parcoursCompletJusquaLaConfirmation() {
        Response started = expect("POST /v1/drafts", 201, startDraft("PRUDENT"));
        started.then()
                .body("draftId", notNullValue())
                .body("status", equalTo("STARTED"))
                .body("product.code", equalTo("PRUDENT"))
                .body("product.annualRate", equalTo("0.020000"))
                .body("confirmationKey", notNullValue());
        String draftId = started.path("draftId");

        expect("PUT /v1/drafts/{draftId}/subscriber", 200,
                csrf().contentType(ContentType.JSON).body(SUBSCRIBER)
                        .when().put("/v1/drafts/" + draftId + "/subscriber"))
                .then()
                .body("status", equalTo("SUBSCRIBER_CAPTURED"))
                .body("subscriber.birthDate", equalTo("1985-04-12"))
                .body("confirmationKey", notNullValue());

        expect("PUT /v1/drafts/{draftId}/simulation", 200,
                csrf().contentType(ContentType.JSON).body(SIMULATION)
                        .when().put("/v1/drafts/" + draftId + "/simulation"))
                .then()
                .body("status", equalTo("SIMULATED"))
                .body("simulation.initialAmount", equalTo("1000.00"))
                .body("simulation.monthlyAmount", equalTo("100.00"))
                .body("simulation.contributed", equalTo("13000.00"))
                .body("simulation.finalCapital", matchesPattern("^[0-9]+\\.[0-9]{2}$"));

        // /recapitulatif GET : l'etat complet du brouillon, porteur de la cle de confirmation.
        String key = expect("GET /v1/drafts/{draftId}", 200,
                given().when().get("/v1/drafts/" + draftId))
                .then()
                .body("subscriber", notNullValue())
                .body("simulation", notNullValue())
                .extract().path("confirmationKey");

        Response confirmed = expect("POST /v1/drafts/{draftId}/confirmation", 201,
                csrf().contentType(ContentType.JSON)
                        .body("{\"confirmationKey\":\"" + key + "\"}")
                        .when().post("/v1/drafts/" + draftId + "/confirmation"));
        confirmed.then()
                .body("reference", notNullValue())
                .body("product.annualRate", equalTo("0.020000"))
                .body("simulation.contributed", equalTo("13000.00"))
                .body("createdAt", notNullValue());
        String createdAt = confirmed.path("createdAt");
        assertDoesNotThrow(() -> Instant.parse(createdAt),
                "createdAt doit etre un instant ISO-8601 ; valeur=" + createdAt);

        // /confirmation GET : la souscription achevee, relue par sa reference.
        String reference = confirmed.path("reference");
        expect("GET /v1/subscriptions/{reference}", 200,
                given().when().get("/v1/subscriptions/" + reference))
                .then()
                .body("reference", equalTo(reference))
                .body("product.code", equalTo("PRUDENT"))
                .body("simulation.finalCapital", matchesPattern("^[0-9]+\\.[0-9]{2}$"));
    }

    // --- /commencer POST : code produit inconnu.

    /**
     * Ecart assume de {@code STRUTS_REST_COVERAGE.md} §1 : la source repondait 400 sur un code
     * produit inconnu, la cible repond <b>404</b>, le contrat gele reservant 400 au corps
     * invalide.
     */
    @Test
    void startDraftRepond404SurUnCodeProduitInconnu() {
        expect("POST /v1/drafts (code inconnu)", 404, startDraft("INCONNU"))
                .then().contentType("application/problem+json");
    }

    /** Corps invalide : 400, et non 404. */
    @Test
    void startDraftRepond400SurUnCorpsInvalide() {
        expect("POST /v1/drafts (corps invalide)", 400,
                csrf().contentType(ContentType.JSON).body("{\"productCode\":\"\"}")
                        .when().post("/v1/drafts"));
    }

    // --- /souscripteur GET et /simulation GET et /recapitulatif GET : getDraft.

    /** Le ré-affichage de chaque etape est une lecture du brouillon. */
    @Test
    void getDraftRendLEtatDeChaqueEtape() {
        String draftId = startedDraftId();

        expect("GET /v1/drafts/{draftId} (etape souscripteur)", 200,
                given().when().get("/v1/drafts/" + draftId))
                .then().body("status", equalTo("STARTED"));

        completedDraftKey(draftId);

        expect("GET /v1/drafts/{draftId} (etape simulation)", 200,
                given().when().get("/v1/drafts/" + draftId))
                .then()
                .body("status", equalTo("SIMULATED"))
                .body("simulation.durationYears", equalTo(10));
    }

    /** Brouillon inconnu ou expire : 404, le contrat ne declarant aucun 410. */
    @Test
    void getDraftRepond404SurUnBrouillonInconnu() {
        expect("GET /v1/drafts/{draftId} (inconnu)", 404,
                given().when().get("/v1/drafts/" + UUID.randomUUID()));
    }

    // --- /souscripteur POST.

    /** Entree invalide : 400 {@code application/problem+json} avec les verdicts par champ. */
    @Test
    void putSubscriberRepond400SurUneEntreeInvalide() {
        String draftId = startedDraftId();

        expect("PUT subscriber (email invalide)", 400,
                csrf().contentType(ContentType.JSON)
                        .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                                + "\"email\":\"pas-un-email\",\"birthDate\":\"1985-04-12\"}")
                        .when().put("/v1/drafts/" + draftId + "/subscriber"))
                .then().contentType("application/problem+json")
                .body("errors", notNullValue());
    }

    /**
     * Le verrou optimiste arbitre deux ecritures concurrentes sur le meme brouillon : la gagnante
     * repond 200, toute perdante repond <b>409 / DRAFT_CONCURRENT_UPDATE</b> et jamais un 500.
     * Remplace le bloc de synchronisation sur la session de la source.
     */
    @Test
    void putSubscriberRepond409SurUneMiseAJourConcurrente() {
        String draftId = startedDraftId();
        int writers = 8;

        List<Response> responses;
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            List<Callable<Response>> writes = IntStream.range(0, writers)
                    .<Callable<Response>>mapToObj(index -> () -> csrf()
                            .contentType(ContentType.JSON)
                            .body("{\"firstName\":\"Claire\",\"lastName\":\"Martin\","
                                    + "\"email\":\"claire." + index + "@example.com\","
                                    + "\"birthDate\":\"1985-04-12\"}")
                            .when().put("/v1/drafts/" + draftId + "/subscriber"))
                    .toList();
            responses = assertDoesNotThrow(() -> pool.invokeAll(writes)).stream()
                    .map(SubscriptionResourceIT::value)
                    .collect(Collectors.toList());
        }

        assertTrue(responses.stream().anyMatch(response -> response.statusCode() == 200),
                "au moins une des ecritures concurrentes doit aboutir");
        assertAll(responses.stream().map(response -> () -> {
            assertTrue(response.statusCode() == 200 || response.statusCode() == 409,
                    "une ecriture concurrente repond 200 ou 409, jamais " + response.statusCode()
                            + " ; corps=" + response.asString());
            assertSecurityHeaders(response, "PUT subscriber concurrent");
            if (response.statusCode() == 409) {
                assertEquals("DRAFT_CONCURRENT_UPDATE", response.path("errors[0].code"),
                        "la perdante doit porter le code de conflit de concurrence");
            }
        }));

        expect("GET /v1/drafts/{draftId} apres concurrence", 200,
                given().when().get("/v1/drafts/" + draftId))
                .then().body("status", equalTo("SUBSCRIBER_CAPTURED"));
    }

    /** Brouillon inconnu sur une ecriture : 404. */
    @Test
    void putSubscriberRepond404SurUnBrouillonInconnu() {
        expect("PUT subscriber (brouillon inconnu)", 404,
                csrf().contentType(ContentType.JSON).body(SUBSCRIBER)
                        .when().put("/v1/drafts/" + UUID.randomUUID() + "/subscriber"));
    }

    // --- /simulation POST.

    /** Montant hors bornes : 400, sans appel au fournisseur de simulation. */
    @Test
    void putSimulationRepond400SurDesMontantsInvalides() {
        String draftId = startedDraftId();
        expect("PUT subscriber", 200, csrf().contentType(ContentType.JSON).body(SUBSCRIBER)
                .when().put("/v1/drafts/" + draftId + "/subscriber"));

        expect("PUT simulation (montant initial sous la borne)", 400,
                csrf().contentType(ContentType.JSON)
                        .body("{\"initialAmount\":\"50.00\",\"monthlyAmount\":\"100.00\","
                                + "\"durationYears\":10}")
                        .when().put("/v1/drafts/" + draftId + "/simulation"))
                .then().contentType("application/problem+json");

        expect("PUT simulation (duree hors bornes)", 400,
                csrf().contentType(ContentType.JSON)
                        .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                                + "\"durationYears\":31}")
                        .when().put("/v1/drafts/" + draftId + "/simulation"));
    }

    /**
     * Simulation avant saisie du souscripteur : conflit d'ordonnancement du parcours, rendu
     * <b>409</b> la ou la source redirigeait vers {@code /souscripteur.do}.
     */
    @Test
    void putSimulationRepond409AvantLaSaisieDuSouscripteur() {
        String draftId = startedDraftId();

        expect("PUT simulation hors sequence", 409,
                csrf().contentType(ContentType.JSON).body(SIMULATION)
                        .when().put("/v1/drafts/" + draftId + "/simulation"))
                .then().contentType("application/problem+json")
                .body("errors[0].code", equalTo("PRECONDITION_SEQUENCE"));
    }

    /** Brouillon inconnu sur la simulation : 404. */
    @Test
    void putSimulationRepond404SurUnBrouillonInconnu() {
        expect("PUT simulation (brouillon inconnu)", 404,
                csrf().contentType(ContentType.JSON).body(SIMULATION)
                        .when().put("/v1/drafts/" + UUID.randomUUID() + "/simulation"));
    }

    // --- /confirmer POST.

    /** 201 a la premiere confirmation, 200 au rejeu, et la meme reference dans les deux cas. */
    @Test
    void confirmDraftRepond201PuisRejoue200AvecLaMemeReference() {
        String draftId = startedDraftId();
        String key = completedDraftKey(draftId);
        String body = "{\"confirmationKey\":\"" + key + "\"}";

        String reference = expect("POST confirmation (premiere fois)", 201,
                csrf().contentType(ContentType.JSON).body(body)
                        .when().post("/v1/drafts/" + draftId + "/confirmation"))
                .path("reference");

        String replayed = expect("POST confirmation (rejeu)", 200,
                csrf().contentType(ContentType.JSON).body(body)
                        .when().post("/v1/drafts/" + draftId + "/confirmation"))
                .path("reference");

        assertEquals(reference, replayed,
                "le rejeu de la meme cle doit rendre la meme reference et ne creer aucune "
                        + "seconde souscription");
    }

    /** Cle de confirmation divergente : 409. */
    @Test
    void confirmDraftRepond409SurUneCleDivergente() {
        String draftId = startedDraftId();
        String key = completedDraftKey(draftId);
        String other = UUID.randomUUID().toString();
        assertNotEquals(key, other, "la cle divergente doit differer de la cle courante");

        expect("POST confirmation (cle divergente)", 409,
                csrf().contentType(ContentType.JSON)
                        .body("{\"confirmationKey\":\"" + other + "\"}")
                        .when().post("/v1/drafts/" + draftId + "/confirmation"))
                .then().contentType("application/problem+json")
                .body("errors[0].code", equalTo("CONFIRMATION_KEY_MISMATCH"));
    }

    /** Parcours incomplet : 409, la ou la source redirigeait vers l'etape manquante. */
    @Test
    void confirmDraftRepond409SurUnParcoursIncomplet() {
        String draftId = startedDraftId();
        String key = expect("GET /v1/drafts/{draftId}", 200,
                given().when().get("/v1/drafts/" + draftId)).path("confirmationKey");

        expect("POST confirmation (parcours incomplet)", 409,
                csrf().contentType(ContentType.JSON)
                        .body("{\"confirmationKey\":\"" + key + "\"}")
                        .when().post("/v1/drafts/" + draftId + "/confirmation"));
    }

    /** Brouillon inconnu sur la confirmation : 404. */
    @Test
    void confirmDraftRepond404SurUnBrouillonInconnu() {
        expect("POST confirmation (brouillon inconnu)", 404,
                csrf().contentType(ContentType.JSON)
                        .body("{\"confirmationKey\":\"" + UUID.randomUUID() + "\"}")
                        .when().post("/v1/drafts/" + UUID.randomUUID() + "/confirmation"));
    }

    // --- /confirmation GET.

    /** Reference inconnue : 404. */
    @Test
    void getSubscriptionRepond404SurUneReferenceInconnue() {
        expect("GET /v1/subscriptions/{reference} (inconnue)", 404,
                given().when().get("/v1/subscriptions/REF-2024-999999"));
    }

    private static Response value(Future<Response> future) {
        return assertDoesNotThrow(() -> future.get());
    }
}

/**
 * Volet protocole de W8-T02 : les lignes de {@code STRUTS_REST_COVERAGE.md} dont la cible est un
 * <b>404 ou un 405 natif de JAX-RS</b>, plus les deux lignes servies par d'autres applications.
 *
 * <p>Classe distincte parce qu'aucune de ces reponses ne touche la base : elles sont donc
 * verifiees sans Dev Services, c'est-a-dire sans daemon de conteneurs. Aucune liste blanche de
 * methodes n'est ecrite a la main : les mappeurs {@code application/problem+json} du plan 22
 * restent en place et le statut vient de JAX-RS.
 */
@QuarkusTest
class SubscriptionResourceProtocolIT {

    private static final String DRAFT = "/v1/drafts/" + UUID.randomUUID();

    private static Response expect(String operation, int status, Response response) {
        assertEquals(status, response.statusCode(),
                operation + " doit repondre " + status + " ; corps=" + response.asString());
        SubscriptionResourceIT.assertSecurityHeaders(response, operation);
        return response;
    }

    /** {@code /commencer} en GET : aucune methode GET declaree sur la collection, 405 natif. */
    @Test
    void getSurLaCollectionDeBrouillonsRend405() {
        expect("GET /v1/drafts", 405, given().when().get("/v1/drafts"));
    }

    /** {@code /recapitulatif} en POST : aucune methode POST declaree sur le brouillon. */
    @Test
    void postSurUnBrouillonRend405() {
        expect("POST /v1/drafts/{draftId}", 405, SubscriptionResourceIT.csrf()
                .contentType(ContentType.JSON).body("{}").when().post(DRAFT));
    }

    /** {@code /confirmer} en GET : aucune methode GET declaree sur la confirmation. */
    @Test
    void getSurLaConfirmationRend405() {
        expect("GET /v1/drafts/{draftId}/confirmation", 405,
                given().when().get(DRAFT + "/confirmation"));
    }

    /** {@code /confirmation} en POST : la ressource de lecture ne declare aucun POST. */
    @Test
    void postSurUneSouscriptionRend405() {
        expect("POST /v1/subscriptions/{reference}", 405, SubscriptionResourceIT.csrf()
                .contentType(ContentType.JSON).body("{}")
                .when().post("/v1/subscriptions/REF-2024-999999"));
    }

    /** Aucune methode DELETE n'est declaree : 405 natif. */
    @Test
    void deleteSurUnBrouillonRend405() {
        expect("DELETE /v1/drafts/{draftId}", 405,
                SubscriptionResourceIT.csrf().when().delete(DRAFT));
    }

    /**
     * Contrepartie JSON de {@code rejectsForgedPostsAndExplainsMissingSession} de la source : une
     * ecriture sans paire double-submit, ou avec une paire divergente, ou avec un jeton au-dela de
     * la borne de 100 caracteres, est refusee en <b>403</b> avant toute mise en correspondance de
     * ressource. La source refusait de meme les POST forges (BASELINE.md §4.7) ; le jeton n'est
     * plus lu dans un formulaire rendu mais pose en en-tete et repete dans le cookie.
     *
     * <p>L'en-tete {@link CsrfDefaultsCallback#OPT_OUT} desarme l'injection de paire par defaut
     * des tests HTTP : sans lui, le rappel poserait une paire valide et le refus ne serait jamais
     * observe.
     */
    @Test
    void uneEcritureSansPaireDoubleSubmitValideRend403() {
        expect("POST /v1/drafts (aucune paire)", 403, given()
                .header(CsrfDefaultsCallback.OPT_OUT, "true")
                .contentType(ContentType.JSON)
                .body("{\"productCode\":\"PRUDENT\"}").when().post("/v1/drafts"));

        expect("POST /v1/drafts (en-tete sans cookie)", 403, given()
                .header(CsrfDefaultsCallback.OPT_OUT, "true")
                .header(CsrfDefenceFilter.TOKEN_HEADER, "jeton-sans-cookie")
                .contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts"));

        expect("POST /v1/drafts (paire divergente)", 403, given()
                .header(CsrfDefaultsCallback.OPT_OUT, "true")
                .header(CsrfDefenceFilter.TOKEN_HEADER, "jeton-en-tete")
                .cookie(CsrfDefenceFilter.TOKEN_COOKIE, "jeton-different")
                .contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts"));

        String tooLong = "x".repeat(CsrfDefenceFilter.MAX_TOKEN_LENGTH + 1);
        expect("POST /v1/drafts (jeton hors borne)", 403, given()
                .header(CsrfDefaultsCallback.OPT_OUT, "true")
                .header(CsrfDefenceFilter.TOKEN_HEADER, tooLong)
                .cookie(CsrfDefenceFilter.TOKEN_COOKIE, tooLong)
                .contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts"));
    }

    /** Ligne « defaut » de la table de couverture : 404 natif sur un chemin non declare. */
    @Test
    void unCheminNonDeclareRend404() {
        expect("GET /v1/drafts/{draftId}/inconnu", 404,
                given().when().get(DRAFT + "/inconnu"));
        expect("GET /inconnu", 404, given().when().get("/inconnu"));
    }

    /**
     * Lignes {@code /catalogue} et {@code /produit} : hors de ce service. Les donnees sont servies
     * par {@code souscription-catalogue-service} et la vue par {@code souscription-web} ; aucune
     * ressource de ce module ne les expose.
     */
    @Test
    void lesCheminsDuCatalogueNeSontPasServisParCeService() {
        expect("GET /v1/products", 404, given().when().get("/v1/products"));
        expect("GET /v1/catalogue", 404, given().when().get("/v1/catalogue"));
    }
}
