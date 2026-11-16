package fr.demo.souscription.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import fr.demo.souscription.api.dto.DraftView;
import fr.demo.souscription.api.dto.ProductDetail;
import fr.demo.souscription.api.dto.SimulationResult;
import fr.demo.souscription.domain.Product;
import fr.demo.souscription.domain.Simulation;
import fr.demo.souscription.domain.Subscriber;
import fr.demo.souscription.domain.SubscriptionDraftEntity;
import fr.demo.souscription.domain.SubscriptionEntity;
import fr.demo.souscription.integration.CatalogueClient;
import fr.demo.souscription.integration.SimulationClient;
import fr.demo.souscription.integration.SimulationComputeRequest;
import fr.demo.souscription.persistence.DraftRepository;
import fr.demo.souscription.persistence.SubscriptionRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Portage de {@code src/test/java/fr/demo/souscription/service/SubscriptionServiceTest.java}
 * (W8-T03).
 *
 * <p>Le source cablait a la main un {@code SubscriptionService} sur un DAO JDBC H2 fichier et un
 * calcul de simulation en memoire. Ici {@link ConfirmationService} est resolu par ArC et ses deux
 * dependances distantes — {@link SimulationClient} (souscription-simulation-service) et
 * {@link CatalogueClient} (souscription-catalogue-service) — sont remplacees par des mocks
 * {@code @InjectMock}. Les depots Panache sont eux aussi bouchonnes en memoire : la persistance
 * reelle sur PostgreSQL est l'objet de {@code persistence/SubscriptionRepositoryTest}, et ce test
 * reste executable sans daemon de conteneurs.
 *
 * <p>Les deux methodes du source sont conservees avec le meme verdict attendu.
 */
@QuarkusTest
@TestProfile(MockedRemoteClientsProfile.class)
class ConfirmationServiceTest {

    private static final Product PRUDENT =
            new Product("PRUDENT", "Horizon Prudent", "Prudent", new BigDecimal("0.020000"));

    @InjectMock
    @RestClient
    SimulationClient simulations;

    @InjectMock
    @RestClient
    CatalogueClient catalogue;

    @InjectMock
    SubscriptionRepository repository;

    @InjectMock
    DraftRepository drafts;

    @Inject
    ConfirmationService confirmationService;

    @Inject
    DraftService draftService;

    private final Map<UUID, SubscriptionDraftEntity> draftRows = new ConcurrentHashMap<>();
    private final Map<UUID, SubscriptionEntity> subscriptionRows = new ConcurrentHashMap<>();

    @BeforeEach
    void inMemoryRepositories() {
        draftRows.clear();
        subscriptionRows.clear();
        doAnswer(call -> {
            SubscriptionDraftEntity entity = call.getArgument(0);
            draftRows.put(entity.draftId, entity);
            return null;
        }).when(drafts).persist(any(SubscriptionDraftEntity.class));
        when(drafts.findByIdOptional(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(draftRows.get(call.<UUID>getArgument(0))));

        doAnswer(call -> {
            SubscriptionEntity entity = call.getArgument(0);
            subscriptionRows.put(entity.confirmationKey, entity);
            return null;
        }).when(repository).persist(any(SubscriptionEntity.class));
        when(repository.findByConfirmationKey(any(UUID.class))).thenAnswer(
                call -> Optional.ofNullable(subscriptionRows.get(call.<UUID>getArgument(0))));

        when(catalogue.findProduct("PRUDENT")).thenReturn(new ProductDetail("PRUDENT",
                "Horizon Prudent", "Prudent", "Hypothese de progression moderee", "0.020000"));
    }

    /**
     * Methode portee : {@code recomputesAuthoritativeResultAndRetainsValuesWhenEditingIdentity}.
     *
     * <p>Meme verdict que le source : modifier l'identite regenere la cle de confirmation sans
     * perdre la simulation ; la projection persistee est celle recalculee par le fournisseur et
     * jamais la valeur portee par le brouillon (999999.00) ; l'identite persistee est la derniere
     * saisie ; rejouer la meme cle renvoie la meme reference. Adaptation : le recalcul autoritaire
     * n'est plus local, il est l'appel distant mocke, verifie avec le taux relu au catalogue.
     */
    @Test
    void recomputesAuthoritativeResultAndRetainsValuesWhenEditingIdentity() {
        when(simulations.compute(any(SimulationComputeRequest.class))).thenReturn(
                new SimulationResult("1000.00", "0.00", 1, "1000.00", "1020.18"));

        UUID draftId = draftService.start("PRUDENT", PRUDENT).draftId();
        draftService.updateSubscriber(draftId,
                new Subscriber("Camille", "Martin", "camille@example.test", LocalDate.of(1980, 1, 1)));
        DraftView simulated = draftService.updateSimulation(draftId,
                new Simulation(new BigDecimal("1000.00"), new BigDecimal("0.00"), 1,
                        BigDecimal.ZERO.setScale(2), new BigDecimal("999999.00")));
        UUID oldKey = simulated.confirmationKey();

        DraftView edited = draftService.updateSubscriber(draftId,
                new Subscriber("Camille", "Durand", "camille@example.test", LocalDate.of(1980, 1, 1)));
        assertNotEquals(oldKey, edited.confirmationKey());
        assertNotNull(edited.simulation(), "la simulation est conservee apres edition de l'identite");

        ConfirmationService.Outcome saved = confirmationService.confirm(draftId, edited.confirmationKey());
        assertTrue(saved.created());
        assertEquals("1000.00", saved.confirmation().simulation().contributed());
        assertNotEquals("999999.00", saved.confirmation().simulation().finalCapital());
        assertEquals("1020.18", saved.confirmation().simulation().finalCapital());
        assertEquals("Durand", saved.confirmation().subscriber().lastName());

        ConfirmationService.Outcome replayed =
                confirmationService.confirm(draftId, edited.confirmationKey());
        assertFalse(replayed.created());
        assertEquals(saved.confirmation().reference(), replayed.confirmation().reference());

        verify(simulations, times(1)).compute(
                new SimulationComputeRequest("1000.00", "0.00", 1, "0.020000"));
        verify(catalogue, times(1)).findProduct("PRUDENT");
        assertEquals(1, subscriptionRows.size(), "une seule souscription pour une cle donnee");
    }

    /**
     * Methode portee : {@code refusesIncompleteJourneysBeforeWriting}.
     *
     * <p>Meme verdict : un parcours sans souscripteur ni simulation est refuse avant toute
     * ecriture. Adaptation : l'{@code IllegalStateException} du source est devenue une
     * {@link DraftStateException} de code {@code PRECONDITION_SEQUENCE} (rendue 409), et aucune
     * dependance distante n'est sollicitee.
     */
    @Test
    void refusesIncompleteJourneysBeforeWriting() {
        DraftView started = draftService.start("PRUDENT", PRUDENT);

        DraftStateException refused = assertThrows(DraftStateException.class,
                () -> confirmationService.confirm(started.draftId(), started.confirmationKey()));

        assertEquals(ConfirmationService.PRECONDITION_SEQUENCE, refused.code());
        verify(repository, never()).persist(any(SubscriptionEntity.class));
        verifyNoInteractions(simulations, catalogue);
        assertTrue(subscriptionRows.isEmpty());
    }
}
