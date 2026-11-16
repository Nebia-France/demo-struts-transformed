package fr.demo.souscription.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.DraftView;
import fr.demo.souscription.api.problem.StorageUnavailableMapper;
import fr.demo.souscription.application.ConfirmationService;
import fr.demo.souscription.application.DraftService;
import fr.demo.souscription.application.SimulationUnavailableException;
import fr.demo.souscription.domain.Product;
import fr.demo.souscription.domain.Simulation;
import fr.demo.souscription.domain.Subscriber;
import fr.demo.souscription.domain.SubscriptionEntity;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.SubscriptionRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Injection de fautes sur l'appel de simulation (W6-T02) : les parametres verifies ici sont ceux
 * de {@code resilience-contract.json} et d'aucune autre source.
 *
 * <p>La garantie centrale est l'absence de souscription partielle : une panne du recalcul est un
 * <b>503 rejouable</b> ({@code SIMULATION_UNAVAILABLE_RETRYABLE}) et <b>zero ligne</b> dans
 * {@code subscriptions.subscriptions}. Aucune valeur de repli n'est persistee, ni recalcul local,
 * ni projection portee par le brouillon.
 *
 * <p>Les assertions de resilience qui n'exigent pas de stockage (timeout, nombre de reessais,
 * seuils du disjoncteur, absence de transaction pendant l'appel) vivent dans
 * {@link SimulationResilienceOfflineTest} : elles restent executees meme sans daemon de
 * conteneurs, alors que cette classe est ignoree lorsque Dev Services ne peut pas demarrer
 * PostgreSQL.
 *
 * <p>Les methodes sont ordonnees : le disjoncteur est un etat partage par le bean, et il est
 * ouvert volontairement par le dernier test.
 */
@QuarkusTest
@TestProfile(SimulationResilienceProfile.class)
@RequiresContainerRuntime
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SimulationResilienceTest {

    private static final Product PRODUCT =
            new Product("PRUDENT", "Horizon Prudent", "Prudent", new BigDecimal("0.020000"));

    private static final Subscriber SUBSCRIBER = new Subscriber("Claire", "Martin",
            "claire.martin@example.com", LocalDate.of(1985, 4, 12));

    private static SimulationStubServer simulationStub;

    private static CatalogueStubServer catalogueStub;

    @Inject
    SimulationLookup simulations;

    @Inject
    DraftService drafts;

    @Inject
    ConfirmationService confirmations;

    @Inject
    SubscriptionRepository repository;

    @BeforeAll
    static void startStubs() throws IOException {
        simulationStub = new SimulationStubServer();
        catalogueStub = new CatalogueStubServer();
    }

    @AfterAll
    static void stopStubs() {
        simulationStub.close();
        catalogueStub.close();
    }

    @BeforeEach
    void resetStubs() {
        simulationStub.reset();
        simulationStub.serveComputation();
        catalogueStub.reset();
        catalogueStub.serveProduct();
        TransactionProbeFilter.reset();
    }

    /** Etape 1 : la valeur persistee est celle du fournisseur, et non celle du brouillon. */
    @Test
    @Order(1)
    void laValeurPersisteeEstCelleDuFournisseur() {
        BigDecimal tampered = new BigDecimal("99999.99");
        DraftView draft = completeDraft(tampered);

        ConfirmationService.Outcome outcome =
                confirmations.confirm(draft.draftId(), draft.confirmationKey());

        String authoritative = SimulationStubServer.expectedFinalCapital("1000.00", "100.00", 10,
                "0.020000");
        SubscriptionEntity stored = QuarkusTransaction.requiringNew()
                .call(() -> repository.findByConfirmationKey(draft.confirmationKey()).orElseThrow());

        assertEquals(authoritative, stored.finalCapital.toPlainString(),
                "final_capital persiste doit etre la valeur retournee par le bouchon de "
                + "simulation, jamais celle envoyee par le client");
        assertEquals(authoritative, outcome.confirmation().simulation().finalCapital());
        assertFalse(tampered.toPlainString().equals(stored.finalCapital.toPlainString()),
                "la valeur portee par le brouillon ne doit jamais etre reprise");
    }

    /** Etape 2 : aucune transaction n'est active pendant l'appel distant (NFG-009). */
    @Test
    @Order(2)
    void aucuneTransactionActivePendantLAppelDistant() {
        DraftView draft = completeDraft(new BigDecimal("12345.67"));
        TransactionProbeFilter.reset();

        confirmations.confirm(draft.draftId(), draft.confirmationKey());

        assertTrue(TransactionProbeFilter.observed() >= 1,
                "la sonde doit avoir observe la requete sortante de simulation, sinon le test ne "
                + "prouve rien");
        assertFalse(TransactionProbeFilter.transactionSeen(),
                "un appel de 3 s dans une transaction retient une connexion du pool "
                + "(max-size 10) pendant toute sa duree");
    }

    /** Etape 3 : panne du recalcul, 503 rejouable et aucune ligne creee. */
    @Test
    @Order(3)
    void uneSimulationIndisponibleNeCreeAucuneSouscription() {
        DraftView draft = completeDraft(new BigDecimal("12345.67"));
        simulationStub.serveSlowly();

        SimulationUnavailableException outage = assertThrows(SimulationUnavailableException.class,
                () -> confirmations.confirm(draft.draftId(), draft.confirmationKey()));

        assertEquals(503, outage.getResponse().getStatus());
        assertEquals("SIMULATION_UNAVAILABLE_RETRYABLE",
                StorageUnavailableMapper.SIMULATION_UNAVAILABLE_RETRYABLE,
                "le code rendu a la SPA distingue la panne du recalcul de celle du stockage");
        assertEquals(0, countByKey(draft.confirmationKey()),
                "aucune confirmation ne doit etre emise avec une projection non verifiee : zero "
                + "ligne partielle");
    }

    /** Etape 4 : disjoncteur ouvert, toujours 503 et toujours aucune ligne. */
    @Test
    @Order(4)
    void leDisjoncteurOuvertRendUn503SansLigneCreee() {
        DraftView draft = completeDraft(new BigDecimal("12345.67"));
        simulationStub.serveStatus(500);

        boolean opened = false;
        for (int call = 0; call < 40 && !opened; call++) {
            simulationStub.reset();
            assertThrows(SimulationUnavailableException.class,
                    () -> simulations.compute(standardRequest()));
            opened = simulationStub.requests() == 0;
        }

        assertTrue(opened,
                "apres 20 appels dont plus de la moitie en echec, le disjoncteur doit s'ouvrir et "
                + "l'appel echouer sans atteindre le fournisseur");
        assertThrows(SimulationUnavailableException.class,
                () -> confirmations.confirm(draft.draftId(), draft.confirmationKey()));
        assertEquals(0, countByKey(draft.confirmationKey()),
                "disjoncteur ouvert : aucune souscription n'est creee");
    }

    private SimulationComputeRequest standardRequest() {
        return new SimulationComputeRequest("1000.00", "100.00", 10, "0.020000");
    }

    private long countByKey(UUID key) {
        return QuarkusTransaction.requiringNew()
                .call(() -> repository.count("confirmationKey", key));
    }

    private DraftView completeDraft(BigDecimal finalCapital) {
        DraftView started = drafts.start("PRUDENT", PRODUCT);
        drafts.updateSubscriber(started.draftId(), SUBSCRIBER);
        return drafts.updateSimulation(started.draftId(),
                new Simulation(new BigDecimal("1000.00"), new BigDecimal("100.00"), 10,
                        new BigDecimal("13000.00"), finalCapital));
    }
}
