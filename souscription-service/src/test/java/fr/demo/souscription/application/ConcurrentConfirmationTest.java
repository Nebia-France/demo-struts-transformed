package fr.demo.souscription.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.DraftView;
import fr.demo.souscription.domain.Product;
import fr.demo.souscription.domain.Simulation;
import fr.demo.souscription.domain.Subscriber;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresWithContractStubsProfile;
import fr.demo.souscription.persistence.SubscriptionRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * W4-T05, mitigation R-004 : la confirmation est idempotente sous concurrence reelle.
 *
 * <p>N requetes simultanees portant la meme {@code confirmationKey} doivent produire
 * <b>une seule</b> ligne dans {@code subscriptions.subscriptions} (CFG-004), la meme
 * {@code reference} pour tous les appelants, et aucune erreur serveur : la course est tranchee
 * par la contrainte {@code UNIQUE (confirmation_key)} puis relue dans une transaction neuve.
 */
@QuarkusTest
@TestProfile(PostgresWithContractStubsProfile.class)
@RequiresContainerRuntime
class ConcurrentConfirmationTest {

    private static final int THREADS = 8;

    private static final Product PRODUCT =
            new Product("PRUDENT", "Horizon Prudent", "Prudent", new BigDecimal("0.020000"));

    @Inject
    DraftService drafts;

    @Inject
    ConfirmationService confirmations;

    @Inject
    SubscriptionRepository repository;

    @Test
    void concurrentConfirmationsOfTheSameKeyCreateASingleSubscription() throws Exception {
        DraftView draft = completeDraft();
        UUID draftId = draft.draftId();
        UUID key = draft.confirmationKey();

        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<ConfirmationService.Outcome>> running = IntStream.range(0, THREADS)
                    .mapToObj(i -> pool.submit((Callable<ConfirmationService.Outcome>) () -> {
                        start.await();
                        // Les lectures hors transaction du service s'appuient sur le contexte
                        // de requete, actif en HTTP mais pas sur un thread brut de l'executeur.
                        ManagedContext requestContext = Arc.container().requestContext();
                        requestContext.activate();
                        try {
                            return confirmations.confirmIdempotent(draftId, key);
                        } catch (RuntimeException unexpected) {
                            failures.add(unexpected);
                            throw unexpected;
                        } finally {
                            requestContext.terminate();
                        }
                    }))
                    .toList();
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(2, TimeUnit.MINUTES),
                    "les confirmations concurrentes doivent toutes aboutir");

            Set<String> references = running.stream()
                    .map(ConcurrentConfirmationTest::value)
                    .map(outcome -> outcome.confirmation().reference())
                    .collect(Collectors.toSet());

            assertTrue(failures.isEmpty(),
                    "aucun appelant ne doit recevoir une erreur serveur : " + failures);
            assertEquals(1, references.size(),
                    "tous les appelants doivent recevoir la meme reference : " + references);
            assertEquals(1, countByKey(key),
                    "une seule ligne doit exister pour une cle de confirmation donnee");
            assertEquals(1, running.stream().map(ConcurrentConfirmationTest::value)
                            .filter(ConfirmationService.Outcome::created).count(),
                    "une seule confirmation doit etre creee, les autres sont des rejeux");
        } finally {
            if (!pool.isShutdown()) {
                pool.shutdownNow();
            }
        }
    }

    private long countByKey(UUID key) {
        return QuarkusTransaction.requiringNew()
                .call(() -> repository.count("confirmationKey", key));
    }

    private DraftView completeDraft() {
        DraftView started = drafts.start("PRUDENT", PRODUCT);
        drafts.updateSubscriber(started.draftId(), new Subscriber("Claire", "Martin",
                "claire.martin@example.com", LocalDate.of(1985, 4, 12)));
        return drafts.updateSimulation(started.draftId(),
                new Simulation(new BigDecimal("1000.00"), new BigDecimal("100.00"), 10,
                        new BigDecimal("13000.00"), new BigDecimal("14321.55")));
    }

    private static ConfirmationService.Outcome value(
            Future<ConfirmationService.Outcome> outcome) {
        try {
            return outcome.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            throw new IllegalStateException(failed.getCause());
        }
    }
}
