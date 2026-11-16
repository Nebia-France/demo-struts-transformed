package fr.demo.souscription.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.application.SimulationUnavailableException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.lang.reflect.Method;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Injection de fautes sur l'appel de simulation, partie qui n'exige pas de base (W6-T02). Les
 * valeurs verifiees sont celles de
 * {@code souscription-service-to-souscription-simulation-service/resilience-contract.json} et
 * d'aucune autre source : timeout 3 s, 1 reessai, disjoncteur (volume 20, ratio 0,5, delai 10 s,
 * seuil 2).
 *
 * <p>La preuve d'une politique de reessai est le <b>journal des requetes du bouchon</b> et non le
 * statut rendu : une reponse est identique qu'une ou deux requetes aient atteint le fournisseur.
 *
 * <p>L'assertion « aucune ligne partielle » vit dans {@link SimulationResilienceTest}, qui exige
 * un stockage reel.
 */
@QuarkusTest
@TestProfile(SimulationResilienceOfflineProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SimulationResilienceOfflineTest {

    private static SimulationStubServer stub;

    @Inject
    SimulationLookup simulations;

    @BeforeAll
    static void startStub() throws IOException {
        stub = new SimulationStubServer();
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @BeforeEach
    void resetStub() {
        stub.reset();
        stub.serveComputation();
        TransactionProbeFilter.reset();
    }

    /** Etape 1 : les parametres du contrat sont portes par le client, valeur pour valeur. */
    @Test
    @Order(1)
    void resilienceAnnotationsMatchTheFrozenContract() throws NoSuchMethodException {
        Method compute = SimulationClient.class.getMethod("compute", SimulationComputeRequest.class);

        Timeout timeout = compute.getAnnotation(Timeout.class);
        assertNotNull(timeout, "sans @Timeout, un fournisseur lent bloque la confirmation");
        assertEquals(3000L, timeout.value(), "timeout impose : 3 s");

        Retry retry = compute.getAnnotation(Retry.class);
        assertNotNull(retry, "le contrat impose 1 reessai");
        assertEquals(1, retry.maxRetries(),
                "maxRetries impose : 1, admissible uniquement parce que le calcul est pur");
        assertTrue(retry.jitter() > 0, "backoff avec gigue impose par le contrat");

        CircuitBreaker breaker = compute.getAnnotation(CircuitBreaker.class);
        assertNotNull(breaker, "sans disjoncteur, chaque confirmation frappe un fournisseur mort");
        assertEquals(20, breaker.requestVolumeThreshold());
        assertEquals(0.5, breaker.failureRatio());
        assertEquals(10_000L, breaker.delay());
        assertEquals(2, breaker.successThreshold());
    }

    /** Etape 2 : aucune transaction n'est active pendant l'appel distant (NFG-009). */
    @Test
    @Order(2)
    void noTransactionActiveDuringRemoteCall() {
        assertNotNull(simulations.compute(standardRequest()).finalCapital());

        assertEquals(1, TransactionProbeFilter.observed(),
                "la sonde doit avoir observe la requete sortante, sinon le test ne prouve rien");
        assertFalse(TransactionProbeFilter.transactionSeen(),
                "un appel de 3 s dans une transaction retient une connexion du pool "
                + "(max-size 10) pendant toute sa duree");
    }

    /** Etape 3 : un 400 n'est jamais reessaye et reste une erreur de parametres. */
    @Test
    @Order(3)
    void unQuatreCentNEstJamaisReessaye() {
        stub.serveStatus(400);

        assertThrows(SimulationRejectedException.class,
                () -> simulations.compute(standardRequest()));
        assertEquals(1, stub.requests(), "toute reponse 4xx est une condition non rejouable");
    }

    /** Etape 4 : un 429 non plus, et il devient une indisponibilite. */
    @Test
    @Order(4)
    void unQuatreCentVingtNeufNEstJamaisReessaye() {
        stub.serveStatus(429);

        assertThrows(SimulationUnavailableException.class,
                () -> simulations.compute(standardRequest()));
        assertEquals(1, stub.requests(), "le 429 ne doit jamais etre reessaye");
    }

    /** Etape 5 : un 5xx est reessaye exactement une fois. */
    @Test
    @Order(5)
    void unCinqCentEstReessayeUneSeuleFois() {
        stub.serveStatus(500);

        assertThrows(SimulationUnavailableException.class,
                () -> simulations.compute(standardRequest()));
        assertEquals(2, stub.requests(), "1 appel + 1 reessai, conformement a maxRetries = 1");
    }

    /** Etape 6 : le timeout de 3 s tranche avant la reponse du fournisseur. */
    @Test
    @Order(6)
    void leDelaiDepasseEstRenduRejouable() {
        stub.serveSlowly();

        long start = System.nanoTime();
        SimulationUnavailableException expired = assertThrows(SimulationUnavailableException.class,
                () -> simulations.compute(standardRequest()));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(503, expired.getResponse().getStatus(),
                "un depassement de delai est un 503 rejouable et jamais un 500");
        assertTrue(elapsedMillis < 9000,
                "le timeout de 3 s doit borner chaque tentative ; mesure=" + elapsedMillis + "ms");
    }

    /** Etape 7 : le disjoncteur s'ouvre aux seuils du contrat. */
    @Test
    @Order(7)
    void leDisjoncteurSOuvreAuxSeuilsDuContrat() {
        stub.serveStatus(500);

        boolean opened = false;
        for (int call = 0; call < 40 && !opened; call++) {
            stub.reset();
            assertThrows(SimulationUnavailableException.class,
                    () -> simulations.compute(standardRequest()));
            opened = stub.requests() == 0;
        }

        assertTrue(opened,
                "apres 20 appels dont plus de la moitie en echec, le disjoncteur doit s'ouvrir et "
                + "l'appel echouer sans atteindre le fournisseur");
    }

    private SimulationComputeRequest standardRequest() {
        return new SimulationComputeRequest("1000.00", "100.00", 10, "0.020000");
    }
}
