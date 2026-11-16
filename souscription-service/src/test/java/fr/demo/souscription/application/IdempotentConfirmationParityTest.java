package fr.demo.souscription.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import fr.demo.souscription.api.dto.DraftView;
import fr.demo.souscription.api.dto.SimulationResult;
import fr.demo.souscription.api.mapper.ModelMapper;
import fr.demo.souscription.domain.Product;
import fr.demo.souscription.domain.Simulation;
import fr.demo.souscription.domain.Subscriber;
import fr.demo.souscription.domain.SubscriptionEntity;
import fr.demo.souscription.integration.SimulationLookup;
import fr.demo.souscription.integration.SimulationComputeRequest;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import fr.demo.souscription.persistence.SubscriptionRepository;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Plan 23 — verification de parite comportementale de la confirmation idempotente.
 *
 * <p>Deux garanties de la source sont reproduites ici sous Hibernate et PostgreSQL
 * (BASELINE.md §4.1, §4.4) :
 * <ul>
 *   <li>une seule souscription et une seule {@code reference} par {@code confirmation_key},
 *       quel que soit le nombre de soumissions, par lecture prealable puis detection de la
 *       violation d'unicite ({@code SQLState 23505}) et relecture ;</li>
 *   <li>la mutation du brouillon est serialisee non plus par un moniteur de session mais par le
 *       verrou optimiste {@code @Version}, la perdante etant rendue
 *       <b>409 / {@code DRAFT_CONCURRENT_UPDATE}</b>.</li>
 * </ul>
 *
 * <p>S'y ajoutent deux invariants propres a la cible : la projection persistee est celle que le
 * serveur recalcule (jamais la valeur portee par le brouillon), et les lectures concurrentes
 * d'un brouillon n'observent jamais un etat partiel sous {@code READ COMMITTED}.
 */
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class IdempotentConfirmationParityTest {

    private static final int THREADS = 8;

    private static final int ATTEMPTS = 20;

    private static final Product PRODUCT =
            new Product("PRUDENT", "Horizon Prudent", "Prudent", new BigDecimal("0.020000"));

    private static final Subscriber FIRST = new Subscriber("Claire", "Martin",
            "claire.martin@example.com", LocalDate.of(1985, 4, 12));

    private static final Subscriber SECOND = new Subscriber("Camille", "Durand",
            "camille.durand@example.com", LocalDate.of(1979, 9, 3));

    @Inject
    DraftService drafts;

    @Inject
    ConfirmationService confirmations;

    @Inject
    SubscriptionRepository repository;

    @Inject
    SimulationLookup simulations;

    /** Etape 1 : N >= 8 confirmations simultanees d'une meme cle, une seule ligne creee. */
    @Test
    void concurrentConfirmationsCreateExactlyOneSubscription() throws Exception {
        DraftView draft = completeDraft(new BigDecimal("14321.55"));
        UUID draftId = draft.draftId();
        UUID key = draft.confirmationKey();

        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<ConfirmationService.Outcome>> running = IntStream.range(0, THREADS)
                    .mapToObj(i -> pool.submit((Callable<ConfirmationService.Outcome>) () -> {
                        start.await();
                        try {
                            return inRequestContext(
                                    () -> confirmations.confirmIdempotent(draftId, key));
                        } catch (RuntimeException unexpected) {
                            failures.add(unexpected);
                            throw unexpected;
                        }
                    }))
                    .toList();
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(2, TimeUnit.MINUTES),
                    "les confirmations concurrentes doivent toutes aboutir");

            Set<String> references = running.stream()
                    .map(IdempotentConfirmationParityTest::value)
                    .map(outcome -> outcome.confirmation().reference())
                    .collect(Collectors.toSet());

            assertTrue(failures.isEmpty(),
                    "aucun appelant ne doit recevoir une erreur serveur : " + failures);
            assertEquals(1, references.size(),
                    "tous les appelants doivent recevoir la meme reference : " + references);
            assertEquals(1, countByKey(key),
                    "une seule ligne doit exister pour une cle de confirmation donnee");
            assertEquals(1, running.stream().map(IdempotentConfirmationParityTest::value)
                            .filter(ConfirmationService.Outcome::created).count(),
                    "une seule confirmation est creee, les autres sont des rejeux");
        } finally {
            if (!pool.isShutdown()) {
                pool.shutdownNow();
            }
        }
    }

    /** Etape 2 : le rejeu sequentiel renvoie la souscription existante sans seconde insertion. */
    @Test
    void sequentialReplayReturnsTheExistingSubscription() {
        DraftView draft = completeDraft(new BigDecimal("14321.55"));
        ConfirmationService.Outcome first =
                confirmations.confirm(draft.draftId(), draft.confirmationKey());
        ConfirmationService.Outcome replay =
                confirmations.confirm(draft.draftId(), draft.confirmationKey());

        assertTrue(first.created(), "la premiere confirmation cree la souscription");
        assertEquals(false, replay.created(), "le rejeu ne cree rien (200 au niveau REST)");
        assertEquals(first.confirmation().reference(), replay.confirmation().reference(),
                "le rejeu renvoie la meme reference");
        assertEquals(1, countByKey(draft.confirmationKey()),
                "le rejeu n'insere pas de seconde ligne");
    }

    /** Etape 6 : la mise a jour concurrente perdante est un 409 / DRAFT_CONCURRENT_UPDATE. */
    @Test
    void concurrentDraftUpdateYields409() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                UUID draftId = drafts.start("PRUDENT", PRODUCT).draftId();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<DraftView>> running = List.of(
                        pool.submit(update(draftId, FIRST, start)),
                        pool.submit(update(draftId, SECOND, start)));
                start.countDown();

                int succeeded = 0;
                DraftStateException conflict = null;
                for (Future<DraftView> outcome : running) {
                    try {
                        outcome.get(1, TimeUnit.MINUTES);
                        succeeded++;
                    } catch (ExecutionException failed) {
                        if (!(failed.getCause() instanceof DraftStateException rejected)) {
                            throw new IllegalStateException(failed.getCause());
                        }
                        conflict = rejected;
                    }
                }

                if (conflict == null) {
                    continue;
                }
                assertEquals(1, succeeded, "une seule des deux mises a jour doit aboutir");
                assertEquals(409, conflict.getResponse().getStatus(),
                        "la mise a jour perdante est un 409 Conflict");
                assertEquals(DraftStateException.DRAFT_CONCURRENT_UPDATE, conflict.code(),
                        "le code d'erreur distingue le conflit de concurrence");
                return;
            }
            fail("aucun conflit de mise a jour concurrente observe en " + ATTEMPTS + " essais");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Etape 9 : la projection persistee est celle que le serveur recalcule, et non la valeur
     * portee par le brouillon — le brouillon peut etre perime ou altere.
     */
    @Test
    void persistedProjectionIsTheRecomputedOne() {
        BigDecimal tampered = new BigDecimal("99999.99");
        DraftView draft = completeDraft(tampered);

        ConfirmationService.Outcome outcome =
                confirmations.confirm(draft.draftId(), draft.confirmationKey());

        SimulationResult authoritative = simulations.compute(new SimulationComputeRequest(
                ModelMapper.amount(new BigDecimal("1000.00")),
                ModelMapper.amount(new BigDecimal("100.00")), 10,
                ModelMapper.rate(new BigDecimal("0.020000"))));
        SubscriptionEntity stored = QuarkusTransaction.requiringNew()
                .call(() -> repository.findByConfirmationKey(draft.confirmationKey()).orElseThrow());

        assertEquals(authoritative.finalCapital(), stored.finalCapital.toPlainString(),
                "final_capital persiste doit etre la valeur recalculee par le serveur");
        assertEquals(authoritative.contributed(), stored.contributed.toPlainString(),
                "contributed persiste doit etre la valeur recalculee par le serveur");
        assertNotEquals(tampered.toPlainString(), stored.finalCapital.toPlainString(),
                "la valeur fournie dans le brouillon ne doit jamais etre reprise");
        assertEquals(authoritative.finalCapital(), outcome.confirmation().simulation().finalCapital(),
                "la reponse expose la meme valeur que la ligne persistee");
    }

    /**
     * Etape 10 : une lecture concurrente d'un brouillon en cours de mise a jour renvoie l'etat
     * d'avant ou celui d'apres, jamais un melange (cle regeneree avec l'ancien souscripteur).
     * {@code READ COMMITTED} suffit, le niveau d'isolation n'est pas releve.
     */
    @Test
    void concurrentReadsNeverSeePartialState() throws Exception {
        UUID draftId = drafts.start("PRUDENT", PRODUCT).draftId();
        DraftView before = drafts.updateSubscriber(draftId, FIRST);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<DraftView> writer = pool.submit(update(draftId, SECOND, start));
            Future<List<DraftView>> reader = pool.submit(() -> {
                start.await();
                List<DraftView> seen = new CopyOnWriteArrayList<>();
                return inRequestContext(() -> {
                    for (int i = 0; i < 200; i++) {
                        drafts.read(draftId).ifPresent(seen::add);
                    }
                    return seen;
                });
            });
            start.countDown();

            DraftView after = writer.get(1, TimeUnit.MINUTES);
            for (DraftView observed : reader.get(1, TimeUnit.MINUTES)) {
                if (FIRST.firstName().equals(observed.subscriber().firstName())) {
                    assertEquals(before.confirmationKey(), observed.confirmationKey(),
                            "l'ancien souscripteur ne peut coexister avec la nouvelle cle");
                } else {
                    assertEquals(SECOND.firstName(), observed.subscriber().firstName(),
                            "seuls les deux etats valides sont observables");
                    assertEquals(after.confirmationKey(), observed.confirmationKey(),
                            "le nouveau souscripteur va avec la nouvelle cle");
                }
            }
            assertNotEquals(before.confirmationKey(), after.confirmationKey(),
                    "toute mutation regenere la cle de confirmation");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Les lectures hors transaction des services s'appuient sur le contexte de requete CDI, actif
     * pour une requete HTTP mais pas sur un thread brut de l'executeur du test.
     */
    private static <T> T inRequestContext(Supplier<T> work) {
        ManagedContext requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            return work.get();
        } finally {
            requestContext.terminate();
        }
    }

    private long countByKey(UUID key) {
        return QuarkusTransaction.requiringNew()
                .call(() -> repository.count("confirmationKey", key));
    }

    private Callable<DraftView> update(UUID draftId, Subscriber subscriber,
                                       CountDownLatch start) {
        return () -> {
            start.await();
            return drafts.updateSubscriber(draftId, subscriber);
        };
    }

    private DraftView completeDraft(BigDecimal finalCapital) {
        DraftView started = drafts.start("PRUDENT", PRODUCT);
        drafts.updateSubscriber(started.draftId(), FIRST);
        return drafts.updateSimulation(started.draftId(),
                new Simulation(new BigDecimal("1000.00"), new BigDecimal("100.00"), 10,
                        new BigDecimal("13000.00"), finalCapital));
    }

    private static ConfirmationService.Outcome value(
            Future<ConfirmationService.Outcome> outcome) {
        try {
            return outcome.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (ExecutionException failed) {
            throw new IllegalStateException(failed.getCause());
        }
    }
}
