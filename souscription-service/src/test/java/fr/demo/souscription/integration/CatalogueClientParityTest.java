package fr.demo.souscription.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.SubscriptionResource;
import fr.demo.souscription.api.dto.ProductDetail;
import fr.demo.souscription.api.dto.StartDraftRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServiceUnavailableException;
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
 * Parite du plan 27 : le passage de la lecture en memoire du catalogue a un appel distant borne.
 *
 * <p>Deux familles de criteres y cohabitent.
 * <ul>
 *   <li><b>Comportement preserve</b> (BASELINE §4.8) : la recherche reste sensible a la casse et
 *       aux espaces ({@code equals}), {@code findProduct(null)} ne leve rien et ne trouve rien, et
 *       le taux annuel garde ses six decimales sur le fil.</li>
 *   <li><b>Nouvelle classe d'echec</b>, sans equivalent dans le source : elle est verifiee contre
 *       la table gelee {@code resilience-contract.json} — timeout 3 s, 2 reessais, disjoncteur
 *       (volume 20, ratio 0,5, delai 10 s, seuil 2), aucun reessai sur 4xx ni sur 429.</li>
 * </ul>
 *
 * <p>La preuve d'une politique de reessai est le <b>journal des requetes du bouchon</b> et non le
 * statut rendu : une reponse est identique qu'une ou trois requetes aient atteint le fournisseur.
 *
 * <p>Les methodes sont ordonnees : le disjoncteur est un etat partage par le bean, et les
 * assertions de reessai exigent un circuit ferme.
 */
@QuarkusTest
@TestProfile(CatalogueParityProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogueClientParityTest {

    private static CatalogueStubServer stub;

    @Inject
    CatalogueLookup lookup;

    @Inject
    SubscriptionResource resource;

    @BeforeAll
    static void startStub() throws IOException {
        stub = new CatalogueStubServer();
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @BeforeEach
    void resetStub() {
        stub.reset();
        stub.serveProduct();
        TransactionProbeFilter.reset();
    }

    /** Etape 1 : les valeurs de resilience sont imposees par le contrat, valeur pour valeur. */
    @Test
    @Order(1)
    void resilienceAnnotationsMatchTheFrozenContract() throws NoSuchMethodException {
        Method findProduct = CatalogueClient.class.getMethod("findProduct", String.class);

        Timeout timeout = findProduct.getAnnotation(Timeout.class);
        assertNotNull(timeout, "sans @Timeout, un catalogue lent bloque le parcours sans borne");
        assertEquals(3000L, timeout.value(), "timeout impose : 3 s");

        Retry retry = findProduct.getAnnotation(Retry.class);
        assertNotNull(retry, "le contrat impose 2 reessais");
        assertEquals(2, retry.maxRetries(), "maxRetries impose : 2");
        assertTrue(retry.jitter() > 0, "backoff avec gigue impose par le contrat");

        CircuitBreaker breaker = findProduct.getAnnotation(CircuitBreaker.class);
        assertNotNull(breaker, "sans disjoncteur, chaque requete frappe un fournisseur mort");
        assertEquals(20, breaker.requestVolumeThreshold());
        assertEquals(0.5, breaker.failureRatio());
        assertEquals(10_000L, breaker.delay());
        assertEquals(2, breaker.successThreshold());
    }

    /** Etape 2 : la semantique de recherche du source a survecu au deplacement. */
    @Test
    @Order(2)
    void lookupSemanticsOfTheSourceArePreserved() {
        ProductDetail prudent = lookup.findProduct("PRUDENT").orElseThrow();
        assertEquals("0.020000", prudent.annualRate(),
                "le taux reste une chaine d'echelle 6 : 0.02 serait une reduction de contrat");

        assertTrue(lookup.findProduct("prudent").isEmpty(),
                "le source comparait par equals : une recherche insensible a la casse changerait "
                + "le produit vendu");
        assertTrue(lookup.findProduct(" PRUDENT ").isEmpty(), "aucun trim() dans le source");
        assertTrue(lookup.findProduct("PRUDENT123").isEmpty(), "aucune correspondance partielle");
        assertTrue(lookup.findProduct(null).isEmpty(),
                "find(null) ne trouvait rien et ne levait rien dans le source");
    }

    /** Etape 3 : un 404 est une condition non rejouable. */
    @Test
    @Order(3)
    void notFoundIsNotRetried() {
        stub.serveNotFound();

        assertTrue(lookup.findProduct("INEXISTANT").isEmpty());
        assertEquals(1, stub.requests(),
                "reessayer une reponse deterministe triple la charge du fournisseur et ouvre le "
                + "disjoncteur sur des erreurs client");
    }

    /** Etape 4 : un 429 non plus. */
    @Test
    @Order(4)
    void tooManyRequestsIsNotRetried() {
        stub.serveStatus(429);

        assertThrows(ServiceUnavailableException.class, () -> lookup.findProduct("PRUDENT"));
        assertEquals(1, stub.requests(),
                "reessayer une limitation de debit est la facon classique de transformer un "
                + "bridage en panne");
    }

    /** Etape 5 : un 5xx est reessaye exactement deux fois (trois tentatives). */
    @Test
    @Order(5)
    void serverErrorIsRetriedTwice() {
        stub.serveStatus(500);

        assertThrows(ServiceUnavailableException.class, () -> lookup.findProduct("PRUDENT"));
        assertEquals(3, stub.requests(), "1 appel + 2 reessais, conformement a maxRetries = 2");
    }

    /** Etape 6 : la traduction des echecs suit le contrat du fournisseur. */
    @Test
    @Order(6)
    void failureMappingFollowsTheProviderContract() {
        stub.serveNotFound();
        NotFoundException unknown = assertThrows(NotFoundException.class,
                () -> resource.startDraft(new StartDraftRequest("INEXISTANT")));
        assertEquals(404, unknown.getResponse().getStatus(),
                "un 404 du catalogue reste un 404 de startDraft : un 500 imputerait une erreur "
                + "client a ce service et inviterait la passerelle a rejouer");

        stub.reset();
        stub.serveStatus(503);
        ServiceUnavailableException outage = assertThrows(ServiceUnavailableException.class,
                () -> resource.startDraft(new StartDraftRequest("PRUDENT")));
        assertEquals(503, outage.getResponse().getStatus(),
                "seul le 503 porte la semantique rejouable sur laquelle le SPA agit");
    }

    /** Etape 7 : le timeout de 3 s est applique et l'appel ne reste pas suspendu. */
    @Test
    @Order(7)
    void timeoutIsEnforced() {
        stub.serveSlowly();

        long start = System.nanoTime();
        ServiceUnavailableException expired = assertThrows(ServiceUnavailableException.class,
                () -> lookup.findProduct("PRUDENT"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(503, expired.getResponse().getStatus(),
                "un depassement de delai est un 503 et non un 500");
        assertTrue(elapsedMillis < 4500,
                "un appel non borne retiendrait un fil de travail et, au moment de la "
                + "confirmation, une connexion de base ; mesure=" + elapsedMillis + "ms");
    }

    /** Etape 10 : aucune transaction n'est active pendant l'appel distant (NFG-009). */
    @Test
    @Order(8)
    void noTransactionActiveDuringRemoteCall() {
        assertTrue(lookup.findProduct("PRUDENT").isPresent());

        assertEquals(1, TransactionProbeFilter.observed(),
                "la sonde doit avoir observe la requete sortante, sinon le test ne prouve rien");
        assertFalse(TransactionProbeFilter.transactionSeen(),
                "un appel de 3 s dans une transaction retient une connexion du pool (max-size 10) "
                + "pendant toute sa duree");
    }
}
