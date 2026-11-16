package fr.demo.souscription.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.DraftView;
import fr.demo.souscription.domain.Product;
import fr.demo.souscription.domain.SubscriptionDraftEntity;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.DraftRepository;
import fr.demo.souscription.persistence.PostgresDevServicesProfile;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Parity skeleton for 13-souscription-service-draft-retention-purge.
 *
 * Baseline reference: BASELINE.md section 4.2 —
 * src/main/webapp/WEB-INF/web.xml &lt;session-timeout&gt;20&lt;/session-timeout&gt;.
 * Plan 02 classified that setting as "removed"; this plan is its NAMED SUCCESSOR. The source bounded
 * the draft lifetime implicitly (the container dropped the per-user state); the target must bound it
 * explicitly and must make an expired draft unreadable IMMEDIATELY, not only after the purge cron.
 *
 * <p>Adapted to this module's test harness: the PostgreSQL Dev Services profile and the
 * container-runtime guard used by every other persistence test, and {@code QuarkusTransaction}
 * instead of {@code @Transactional} helper methods (self-invocation inside a test class is not
 * intercepted, so the transaction would never start). No assertion is weakened.
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class DraftRetentionParityTest {

    private static final Product PRUDENT =
            new Product("PRUDENT", "Horizon Prudent", "Prudent", new BigDecimal("0.020000"));

    @Inject
    DraftService drafts;

    @Inject
    DraftRetentionJob job;

    @Inject
    DraftRepository repository;

    @Inject
    MeterRegistry registry;

    @Test
    void retentionIsExactlyTwentyMinutes() {
        DraftView started = drafts.start("PRUDENT", PRUDENT);
        SubscriptionDraftEntity entity = find(started.draftId());
        assertEquals(Duration.ofMinutes(20),
                Duration.between(entity.createdAt, entity.expiresAt).withNanos(0),
                "horizon.draft.retention must be PT20M — strict equivalence with the former "
                + "20-minute container timeout, not 'about 20 minutes'");
    }

    @Test
    void purgeDeletesOnlyExpiredDrafts() {
        UUID expired = drafts.start("PRUDENT", PRUDENT).draftId();
        UUID live = drafts.start("PRUDENT", PRUDENT).draftId();
        expire(expired);

        job.purgeExpiredDrafts();

        assertTrue(findOptional(expired).isEmpty(), "the expired draft must be purged");
        assertTrue(findOptional(live).isPresent(),
                "a live draft must survive the purge: deleting it destroys an in-progress journey, "
                + "which the source never did (only the user's own inactivity ended the journey)");
    }

    @Test
    void purgeIncrementsTheCounterByTheDeletedRowCount() {
        purgeAll();
        double before = counter();
        UUID first = drafts.start("PRUDENT", PRUDENT).draftId();
        UUID second = drafts.start("PRUDENT", PRUDENT).draftId();
        expire(first);
        expire(second);

        job.purgeExpiredDrafts();

        assertEquals(before + 2, counter(), 0.001,
                "horizon.draft.purged must increase by the number of DELETED ROWS, not by one per "
                + "run: the R-002 accumulation alert compares volumes. A silently failing purge is "
                + "invisible without this counter and the table grows without bound.");
    }

    @Test
    void expiredDraftIsUnreadableBeforePurge() {
        DraftView started = drafts.start("PRUDENT", PRUDENT);
        expire(started.draftId());

        // The row still exists: the purge cron runs every 5 minutes.
        assertTrue(findOptional(started.draftId()).isPresent(), "precondition: the row still exists");
        assertTrue(drafts.read(started.draftId()).isEmpty(),
                "the read path must filter on expiry as well, otherwise an expired draft stays "
                + "usable for up to one cron interval past its retention");
    }

    private double counter() {
        var c = registry.find("horizon.draft.purged").counter();
        return c == null ? 0d : c.count();
    }

    private void purgeAll() {
        QuarkusTransaction.requiringNew().run(repository::deleteAll);
    }

    private void expire(UUID draftId) {
        QuarkusTransaction.requiringNew().run(() -> {
            SubscriptionDraftEntity entity = repository.findByIdOptional(draftId)
                    .orElseThrow(() -> new AssertionError("draft row not found: " + draftId));
            entity.expiresAt = Instant.now().minusSeconds(60);
            repository.persist(entity);
        });
    }

    private SubscriptionDraftEntity find(UUID draftId) {
        return findOptional(draftId)
                .orElseThrow(() -> new AssertionError("draft row not found: " + draftId));
    }

    private Optional<SubscriptionDraftEntity> findOptional(UUID draftId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            repository.getEntityManager().clear();
            return repository.findByIdOptional(draftId);
        });
    }
}
