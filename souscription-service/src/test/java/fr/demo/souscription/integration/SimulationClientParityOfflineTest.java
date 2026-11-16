package fr.demo.souscription.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.SimulationResult;
import fr.demo.souscription.application.ConfirmationService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parite du plan 28, criteres verifiables sans base de donnees.
 *
 * <p>Le recalcul autoritaire de {@code SubscriptionService.confirm} (BASELINE §4.3) est devenu un
 * appel distant. Les criteres qui portent sur le <b>fil</b> et sur la <b>forme du chemin
 * d'appel</b> — valeurs de resilience, taux du catalogue reellement envoye, echelles preservees
 * dans les deux sens, absence de reessai et de repli sur l'ecriture, absence de transaction
 * pendant l'appel — ne demandent aucune persistance et sont verifies ici.
 *
 * <p>Les criteres qui exigent une ligne persistee (valeur persistee = valeur distante, 503 sans
 * ligne, une seule tentative d'ecriture) sont dans {@link SimulationClientParityTest}, qui
 * requiert Dev Services PostgreSQL.
 */
@QuarkusTest
@TestProfile(SimulationParityProfile.class)
class SimulationClientParityOfflineTest {

    private static final Path SOURCES = Path.of("src/main/java/fr/demo/souscription");

    private static SimulationStubServer simulation;
    private static CatalogueStubServer catalogue;

    @Inject
    SimulationLookup simulations;

    @Inject
    CatalogueLookup products;

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

    /** Etape 1 : les valeurs de resilience sont imposees par le contrat, valeur pour valeur. */
    @Test
    void resilienceAnnotationsMatchTheFrozenContract() throws NoSuchMethodException {
        Method compute = SimulationClient.class.getMethod("compute", SimulationComputeRequest.class);

        Timeout timeout = compute.getAnnotation(Timeout.class);
        assertNotNull(timeout, "sans @Timeout, une simulation lente bloque la confirmation");
        assertEquals(3000L, timeout.value(), "timeout impose : 3 s");

        Retry retry = compute.getAnnotation(Retry.class);
        assertNotNull(retry, "le contrat impose 1 reessai");
        assertEquals(1, retry.maxRetries(),
                "asymetrie deliberee avec le catalogue (2) : cet appel est sur le chemin de "
                + "confirmation et sa latence entre dans le budget entrant de 5 s");
        assertTrue(retry.jitter() > 0, "backoff avec gigue impose par le contrat");

        CircuitBreaker breaker = compute.getAnnotation(CircuitBreaker.class);
        assertNotNull(breaker, "sans disjoncteur, chaque confirmation frappe un fournisseur mort");
        assertEquals(20, breaker.requestVolumeThreshold());
        assertEquals(0.5, breaker.failureRatio());
        assertEquals(10_000L, breaker.delay());
        assertEquals(2, breaker.successThreshold());
    }

    /**
     * Etape 3 : le taux porte par la requete est celui du <b>catalogue</b>, avec les quatre noms
     * de champ geles. La reponse serait identique avec un taux fourni par le client : seul le
     * journal du bouchon peut faire cette assertion.
     */
    @Test
    void requestCarriesTheAuthoritativeRate() {
        String authoritative = products.findProduct("PRUDENT").orElseThrow().annualRate();
        assertEquals("0.020000", authoritative, "taux autoritaire du catalogue");

        simulations.compute(new SimulationComputeRequest("1000.00", "100.00", 10, authoritative));

        String body = simulation.lastRequestBody();
        assertNotNull(body, "le fournisseur doit avoir ete appele, sinon le test ne prouve rien");
        assertTrue(body.contains("\"annualRate\":\"0.020000\""),
                "le service de simulation ne lit jamais le catalogue : un taux errone ou fourni "
                + "par le client changerait silencieusement la projection vendue ; corps=" + body);
        for (String field : List.of("initialAmount", "monthlyAmount", "durationYears",
                "annualRate")) {
            assertTrue(body.contains("\"" + field + "\""),
                    "champ gele de SimulationComputeRequest.json absent du fil : " + field
                    + " ; le fournisseur deserialise par nom et calculerait sur un null");
        }
    }

    /**
     * Etape 5 (moitie statique) : aucun {@code @Retry} ni {@code @Fallback} sur le chemin de
     * confirmation. Un reessai sur une ecriture non idempotente risque un doublon invisible pour
     * l'appelant ; un repli masque une perte de donnees.
     */
    @Test
    void confirmationPathCarriesNoRetryNorFallback() {
        assertFalse(ConfirmationService.class.isAnnotationPresent(Retry.class));
        assertFalse(ConfirmationService.class.isAnnotationPresent(Fallback.class));
        for (Method method : ConfirmationService.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(Retry.class),
                    "@Retry interdit sur le chemin de confirmation : " + method.getName());
            assertFalse(method.isAnnotationPresent(Fallback.class),
                    "@Fallback interdit sur une ecriture : il annonce une confirmation qui n'a "
                    + "pas eu lieu ; " + method.getName());
        }
    }

    /**
     * Etape 6 : la justification de la surete du reessai sur un POST est enregistree a cote de
     * {@code maxRetries}. Sans elle, un effet de bord ajoute un jour cote fournisseur ferait du
     * reessai un risque de double effet, sans aucun signal.
     */
    @Test
    void retrySafetyJustificationIsRecorded() throws IOException {
        String client = read(SOURCES.resolve("integration/SimulationClient.java"));
        int annotation = client.indexOf("@Retry(");
        assertTrue(annotation > 0, "@Retry absent du client");
        String preamble = client.substring(0, annotation);
        assertTrue(preamble.contains("maxRetries"),
                "la note doit citer maxRetries avant l'annotation");
        assertTrue(preamble.contains("effet de bord"),
                "la note doit dire que le reessai n'est admissible que parce que le calcul est "
                + "sans effet de bord");
        assertTrue(preamble.contains("maxRetries = 1} devient incorrect")
                        || preamble.contains("ramene a 0"),
                "la note doit dire que maxRetries doit passer a 0 si l'operation acquiert un "
                + "effet de bord");
    }

    /** Etape 7 : aucune transaction n'est active au moment ou la requete sortante part. */
    @Test
    void noTransactionActiveDuringRemoteCall() {
        simulations.compute(new SimulationComputeRequest("1000.00", "100.00", 10, "0.020000"));

        assertEquals(1, TransactionProbeFilter.observed(),
                "la sonde doit avoir observe la requete sortante, sinon le test ne prouve rien");
        assertFalse(TransactionProbeFilter.transactionSeen(),
                "un appel de 3 s plus un reessai retiendrait une connexion du pool (max-size 10) "
                + "pendant plus de 6 s");
    }

    /** Etape 8 : le calcul en memoire a disparu du code principal. */
    @Test
    void inProcessSimulationServiceIsGone() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            List<String> offenders = files
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(SimulationClientParityOfflineTest::mentionsSimulationService)
                    .map(Path::toString)
                    .toList();
            assertTrue(offenders.isEmpty(),
                    "deux implementations de l'arithmetique divergeraient et le service "
                    + "prefererait silencieusement la locale : " + offenders);
        }
    }

    /**
     * Etape 10 : un 400 du fournisseur reste une erreur client, porte ses verdicts par champ et
     * n'est pas reessaye.
     */
    @Test
    void providerValidationFailureIsRelayedAsClientError() {
        simulation.serveRejectionWithFieldErrors();

        SimulationRejectedException refused = assertThrows(SimulationRejectedException.class,
                () -> simulations.compute(
                        new SimulationComputeRequest("1000.00", "100.00", 10, "0.020000")),
                "un 4xx distant ne devient jamais un 5xx local : un 500 imputerait la faute a ce "
                + "service et inviterait la passerelle a rejouer une requete deterministe");
        assertEquals(1, simulation.requests(), "un 400 n'est pas reessaye (condition non "
                + "rejouable du contrat)");
        assertEquals(1, refused.relayed().size(),
                "les verdicts par champ du fournisseur doivent etre relayes : ce sont les erreurs "
                + "que le formulaire source affichait a cote du champ fautif");
        assertEquals("durationYears", refused.relayed().get(0).field());
        assertEquals("DURATION_YEARS_RANGE", refused.relayed().get(0).code());
    }

    /** Etape 11 : montants et taux restent des chaines a echelle preservee dans les deux sens. */
    @Test
    void amountsAreScalePreservingStringsBothWays() {
        SimulationResult result = simulations.compute(
                new SimulationComputeRequest("1000.00", "100.00", 10, "0.020000"));

        String body = simulation.lastRequestBody();
        assertTrue(body.contains("\"1000.00\"") && body.contains("\"0.020000\""),
                "un nombre JSON perdrait les echelles exigees par DECIMAL(14,2) et par le schema "
                + "gele du taux ; corps=" + body);
        assertEquals(2, new BigDecimal(result.finalCapital()).scale(),
                "finalCapital revient a l'echelle 2, sans notation scientifique");
        assertEquals(2, new BigDecimal(result.contributed()).scale());
    }

    private static boolean mentionsSimulationService(Path file) {
        try {
            return read(file).contains("SimulationService");
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
