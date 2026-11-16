package fr.demo.souscription.observability;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.persistence.RequiresContainerRuntime;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.WithTestResource;
import fr.demo.souscription.integration.ContractStubsResource;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verification de parite du plan 26 (metriques Prometheus et tracage), volet exigeant une base
 * reelle : le jeu de familles par defaut complet (dont {@code agroal_active_count}) et les cinq
 * compteurs metier mesures sur un parcours complet.
 *
 * <p>Deux pieges couverts ici :
 * <ul>
 *   <li><b>identite du compteur de doublons</b> : {@code horizon.confirmations.duplicate} doit
 *       etre le meter incremente par le chemin idempotent du plan 23. Un second meter sous un nom
 *       voisin ferait lire zero indefiniment au seul indicateur de production de l'idempotence ;</li>
 *   <li><b>unicite du comptage des rejets</b> : l'increment appartient a
 *       {@code ConstraintViolationMapper} et a lui seul.</li>
 * </ul>
 *
 * <p>Les assertions qui ne dependent d'aucune base (pre-declaration des compteurs, etiquette de
 * statut du SLI, continuation d'un {@code traceparent} entrant, unicite du comptage des rejets)
 * sont rejouees hors conteneur par {@link MetricsParityOfflineTest}.
 */
@EnabledUnlessPropertyFalse(named = "quarkus.micrometer.enabled",
        reason = "chemin de retour arriere de la vague 5 : sans registre de metriques il n'y a aucune serie a verifier ; le comportement metier est verifie par les autres tests, qui passent avec l'instrumentation desarmee")
@EnabledUnlessPropertyFalse(named = "quarkus.otel.enabled",
        reason = "l'assertion de continuation de trace exige l'extension OpenTelemetry armee")
@QuarkusTest
@TestProfile(MetricsParityTest.MetricsJourneyProfile.class)
@RequiresContainerRuntime
@WithTestResource(ContractStubsResource.class)
class MetricsParityTest {

    /** Base reelle via Dev Services ; le SDK OpenTelemetry est arme sans exporteur. */
    public static class MetricsJourneyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("quarkus.datasource.active", "true"),
                    Map.entry("quarkus.hibernate-orm.active", "true"),
                    Map.entry("quarkus.datasource.devservices.enabled", "true"),
                    Map.entry("quarkus.datasource.devservices.image-name", "postgres:16-alpine"),
                    Map.entry("quarkus.datasource.metrics.enabled", "true"),
                    Map.entry("quarkus.otel.sdk.disabled", "false"),
                    Map.entry("quarkus.otel.traces.exporter", "none"),
                    Map.entry("quarkus.otel.metrics.exporter", "none"),
                    Map.entry("quarkus.otel.logs.exporter", "none"),
                    Map.entry("quarkus.otel.traces.sampler", "always_on"),
                    Map.entry("quarkus.otel.traces.sampler.arg", "1"));
        }
    }

    @Inject
    MeterRegistry registry;

    @Test
    void defaultMetricFamiliesAreExposed() {
        // Une requete applicative d'abord : l'histogramme du serveur HTTP et les series du pool
        // n'existent qu'apres une premiere requete observee.
        startDraft();
        String body = scrape();
        for (String family : new String[] {
                "http_server_requests_seconds", "agroal_active_count", "jvm_memory_"}) {
            assertTrue(body.contains(family),
                    "/q/metrics doit exposer la famille par defaut " + family
                    + " ; l'absence d'agroal_active_count desarme aussi silencieusement les "
                    + "alertes de pool du plan 09");
        }
    }

    @Test
    void businessCountersReflectOneJourney() {
        double draftsBefore = count(BusinessMetrics.DRAFTS_CREATED);
        double confirmedBefore = count(BusinessMetrics.CONFIRMATIONS);
        double rejectedBefore = count(BusinessMetrics.VALIDATION_REJECTED);

        String draftId = startDraft();
        putSubscriber(draftId, "Claire", "claire.martin@example.com");
        String key = simulate(draftId);
        confirm(draftId, key, 201);
        // Un rejet de validation : tranche avant tout acces au stockage.
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"\",\"lastName\":\"Martin\","
                        + "\"email\":\"claire.martin@example.com\",\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(400);

        assertTrue(count(BusinessMetrics.DRAFTS_CREATED) - draftsBefore >= 1d,
                BusinessMetrics.DRAFTS_CREATED + " doit compter le brouillon ouvert");
        assertTrue(count(BusinessMetrics.CONFIRMATIONS) - confirmedBefore >= 1d,
                BusinessMetrics.CONFIRMATIONS + " doit compter la confirmation enregistree");
        assertTrue(count(BusinessMetrics.VALIDATION_REJECTED) - rejectedBefore >= 1d,
                BusinessMetrics.VALIDATION_REJECTED + " doit compter la requete refusee");
        // Les deux compteurs restants doivent exister dans le registre, meme a zero : une serie
        // absente est indistinguable d'un scrape casse et fait afficher « no data ».
        assertNotNull(registry.find(BusinessMetrics.DUPLICATES).counter(),
                BusinessMetrics.DUPLICATES + " doit etre enregistre des le demarrage");
        assertNotNull(registry.find(BusinessMetrics.DRAFT_PURGED).counter(),
                BusinessMetrics.DRAFT_PURGED + " doit etre enregistre des le demarrage");

        String body = scrape();
        for (String series : new String[] {"horizon_drafts_created_total",
                "horizon_confirmations_succeeded_total", "horizon_confirmations_duplicate_total",
                "horizon_validation_rejected_total", "horizon_draft_purged_total"}) {
            assertTrue(body.contains(series), "/q/metrics doit exposer " + series);
        }
    }

    @Test
    void duplicateCounterIsTheIdempotencyMeter() {
        double before = count(BusinessMetrics.DUPLICATES);

        String draftId = startDraft();
        putSubscriber(draftId, "Hugo", "hugo.leroy@example.com");
        String key = simulate(draftId);
        confirm(draftId, key, 201);
        // Rejeu de la meme cle : court-circuite par le chemin idempotent du plan 23.
        confirm(draftId, key, 200);

        assertEquals(1.0d, count(BusinessMetrics.DUPLICATES) - before, 0.0d,
                "le rejeu doit incrementer exactement une fois le meter "
                + BusinessMetrics.DUPLICATES + " ; si le plan 23 incrementait un nom voisin, les "
                + "deux series liraient zero et l'operateur en conclurait que l'idempotence n'est "
                + "jamais exercee");
    }

    @Test
    void validationRejectionsAreCountedOnce() {
        double before = count(BusinessMetrics.VALIDATION_REJECTED);

        // Deux violations dans la meme requete : une requete refusee = un increment.
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"\",\"lastName\":\"\",\"email\":\"pas-une-adresse\","
                        + "\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + java.util.UUID.randomUUID() + "/subscriber")
                .then().statusCode(400);

        assertEquals(1.0d, count(BusinessMetrics.VALIDATION_REJECTED) - before, 0.0d,
                "le compteur est incremente par ConstraintViolationMapper et nulle part ailleurs : "
                + "un increment par ressource double-compterait une requete portant deux "
                + "violations et manquerait les ressources ajoutees plus tard");
    }

    @Test
    void availabilitySliIsComputable() {
        startDraft();
        String body = scrape();

        String sample = body.lines()
                .filter(line -> line.startsWith("http_server_requests_seconds_count"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "http_server_requests_seconds_count absent : sans cette serie le SLI de "
                        + "disponibilite 99,9 % n'est calculable d'aucune facon"));
        assertTrue(sample.contains("status=") || sample.contains("outcome="),
                "la serie doit porter une etiquette status ou outcome pour partitionner succes et "
                + "erreurs ; sans elle l'objectif de 99,9 % est non verifiable. Vu : " + sample);
    }

    private String scrape() {
        return given().when().get("/q/metrics").then().statusCode(200).extract().asString();
    }

    private String startDraft() {
        return given().contentType(ContentType.JSON).body("{\"productCode\":\"PRUDENT\"}")
                .when().post("/v1/drafts").then().statusCode(201).extract().path("draftId");
    }

    private void putSubscriber(String draftId, String firstName, String email) {
        given().contentType(ContentType.JSON)
                .body("{\"firstName\":\"" + firstName + "\",\"lastName\":\"Martin\",\"email\":\""
                        + email + "\",\"birthDate\":\"1985-04-12\"}")
                .when().put("/v1/drafts/" + draftId + "/subscriber")
                .then().statusCode(200);
    }

    private String simulate(String draftId) {
        return given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                        + "\"durationYears\":10}")
                .when().put("/v1/drafts/" + draftId + "/simulation")
                .then().statusCode(200).extract().path("confirmationKey");
    }

    private void confirm(String draftId, String key, int expectedStatus) {
        given().contentType(ContentType.JSON).body("{\"confirmationKey\":\"" + key + "\"}")
                .when().post("/v1/drafts/" + draftId + "/confirmation")
                .then().statusCode(expectedStatus);
    }

    /**
     * Somme des series d'un compteur, etiquettes comprises : les doublons sont etiquetes par
     * chemin ({@code read} / {@code conflict}), lire une seule serie manquerait la moitie des
     * rejeux.
     */
    private double count(String name) {
        return registry.find(name).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }
}
