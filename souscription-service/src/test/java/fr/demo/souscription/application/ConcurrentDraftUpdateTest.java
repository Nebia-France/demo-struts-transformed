package fr.demo.souscription.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import fr.demo.souscription.api.dto.DraftView;
import fr.demo.souscription.domain.Product;
import fr.demo.souscription.domain.Subscriber;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.PostgresDevServicesProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * W4-T06, mitigation R-004 : le bloc de synchronisation sur la session de la source est remplace par le
 * verrou optimiste {@code @Version} du brouillon.
 *
 * <p>Deux mises a jour concurrentes du meme brouillon : l'une aboutit, l'autre est rejetee en
 * <b>409 Conflict</b> avec le code {@code DRAFT_CONCURRENT_UPDATE}, et la lecture du brouillon
 * reste coherente. La course n'est pas garantie a chaque essai ; plusieurs essais sont tentes,
 * chacun sur un brouillon neuf, et le premier conflit observe porte les assertions.
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class ConcurrentDraftUpdateTest {

    private static final int ATTEMPTS = 20;

    private static final Product PRODUCT =
            new Product("PRUDENT", "Horizon Prudent", "Prudent", new BigDecimal("0.020000"));

    private static final Subscriber FIRST = new Subscriber("Claire", "Martin",
            "claire.martin@example.com", LocalDate.of(1985, 4, 12));

    private static final Subscriber SECOND = new Subscriber("Camille", "Durand",
            "camille.durand@example.com", LocalDate.of(1979, 9, 3));

    @Inject
    DraftService drafts;

    @Test
    void theLosingConcurrentUpdateIsA409DraftConcurrentUpdate() throws Exception {
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
                    assertEquals(2, succeeded,
                            "sans conflit, les deux mises a jour doivent aboutir");
                    continue;
                }

                assertEquals(1, succeeded, "une seule des deux mises a jour doit aboutir");
                assertEquals(409, conflict.getResponse().getStatus(),
                        "la mise a jour perdante est un 409 Conflict");
                assertEquals(DraftStateException.DRAFT_CONCURRENT_UPDATE, conflict.code());
                assertTrue(drafts.read(draftId).isPresent(),
                        "le brouillon reste lisible et coherent apres le conflit");
                return;
            }
            fail("aucun conflit de mise a jour concurrente observe en " + ATTEMPTS + " essais");
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<DraftView> update(UUID draftId, Subscriber subscriber,
                                       CountDownLatch start) {
        return () -> {
            start.await();
            return drafts.updateSubscriber(draftId, subscriber);
        };
    }
}
