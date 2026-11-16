package fr.demo.souscription.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * W2-T05 : la purge planifiee ne supprime que les brouillons dont {@code expires_at} est passe,
 * et compte les lignes supprimees dans {@code horizon.draft.purged}.
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class DraftRetentionJobTest {

    private static final Product PRODUCT =
            new Product("PRUDENT", "Horizon Prudent", "Prudent", new BigDecimal("0.020000"));

    @Inject
    DraftRetentionJob job;

    @Inject
    DraftRepository drafts;

    @Inject
    Clock clock;

    @Inject
    MeterRegistry registry;

    @Test
    void purgeDeletesOnlyTheExpiredDraftAndCountsIt() {
        SubscriptionDraftEntity expired = SubscriptionDraftEntity.create(PRODUCT,
                clock.instant().minus(Duration.ofHours(2)), Duration.ofMinutes(20));
        SubscriptionDraftEntity active = SubscriptionDraftEntity.create(PRODUCT,
                clock.instant(), Duration.ofMinutes(20));

        QuarkusTransaction.requiringNew().run(() -> {
            drafts.deleteAll();
            drafts.persist(expired);
            drafts.persist(active);
        });

        double before = registry.counter("horizon.draft.purged").count();

        job.purgeExpiredDrafts();

        assertEquals(1.0d, registry.counter("horizon.draft.purged").count() - before, 0.0d,
                "exactement une ligne purgee doit etre comptee");

        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(drafts.findByIdOptional(expired.draftId).isEmpty(),
                    "le brouillon expire doit avoir ete supprime");
            assertFalse(drafts.findByIdOptional(active.draftId).isEmpty(),
                    "le brouillon encore valide doit subsister");
        });
    }
}
