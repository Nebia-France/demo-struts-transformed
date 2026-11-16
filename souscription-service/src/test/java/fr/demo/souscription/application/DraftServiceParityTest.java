package fr.demo.souscription.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.DraftView;
import fr.demo.souscription.domain.Product;
import fr.demo.souscription.domain.Simulation;
import fr.demo.souscription.domain.Subscriber;
import fr.demo.souscription.domain.SubscriptionDraftEntity;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.DraftRepository;
import fr.demo.souscription.persistence.PostgresDevServicesProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Parity skeleton for 12-souscription-service-draft-service.
 *
 * Baseline reference: BASELINE.md section 4.2 — model/Draft.java.
 * setSubscriber AND setSimulation each regenerate confirmationKey. That invariant is the funnel's
 * anti-replay guarantee and the subject of the Gherkin scenario
 * "Modification du souscripteur apres simulation invalide la cle de confirmation".
 *
 * <p>Adapted to this module's test harness: the PostgreSQL Dev Services profile and the
 * container-runtime guard used by every other persistence test, and {@code QuarkusTransaction}
 * instead of {@code @Transactional} helper methods (self-invocation inside a test class is not
 * intercepted, so the transaction would never start). No assertion is weakened.
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class DraftServiceParityTest {

    private static final Product PRUDENT =
            new Product("PRUDENT", "Horizon Prudent", "Prudent", new BigDecimal("0.020000"));

    @Inject
    DraftService drafts;

    @Inject
    DraftRepository repository;

    @Test
    void bothUpdatePathsRegenerateTheConfirmationKey() {
        DraftView started = drafts.start("PRUDENT", PRUDENT);
        UUID k0 = started.confirmationKey();

        DraftView withSubscriber = drafts.updateSubscriber(started.draftId(), subscriber());
        UUID k1 = withSubscriber.confirmationKey();
        assertNotEquals(k0, k1,
                "updateSubscriber must regenerate the confirmation key (source: Draft.setSubscriber)");

        DraftView withSimulation = drafts.updateSimulation(started.draftId(), simulation());
        UUID k2 = withSimulation.confirmationKey();
        assertNotEquals(k1, k2,
                "updateSimulation must regenerate the confirmation key (source: Draft.setSimulation) "
                + "— a half-implemented invariant lets a stale key confirm changed data");
        assertNotEquals(k0, k2);

        // The regeneration must be part of the SAME unit of work as the update, so the committed row
        // already carries the new key. A separate write could fail and leave the old key valid.
        assertEquals(k2, persistedKey(started.draftId()),
                "the persisted row must carry the regenerated key after the update transaction");
    }

    @Test
    void funnelStateTransitions() {
        DraftView started = drafts.start("PRUDENT", PRUDENT);
        assertEquals("STARTED", started.status());
        assertTrue(started.subscriber() == null, "no subscriber right after start");
        assertTrue(started.simulation() == null, "no simulation right after start");
        assertFalse(drafts.isReadyForSummary(started));

        DraftView s1 = drafts.updateSubscriber(started.draftId(), subscriber());
        assertEquals("SUBSCRIBER_CAPTURED", s1.status());
        assertFalse(drafts.isReadyForSummary(s1),
                "the source redirected to /simulation.do while the simulation was null");

        DraftView s2 = drafts.updateSimulation(started.draftId(), simulation());
        assertEquals("SIMULATED", s2.status());
        assertTrue(drafts.isReadyForSummary(s2));
    }

    @Test
    void simulationBeforeSubscriberIsRejected() {
        DraftView started = drafts.start("PRUDENT", PRUDENT);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> drafts.updateSimulation(started.draftId(), simulation()),
                "the source redirected to /souscripteur.do instead of accepting the simulation");
    }

    @Test
    void expiredDraftReadsAsAbsent() {
        DraftView started = drafts.start("PRUDENT", PRUDENT);
        expire(started.draftId());

        Optional<DraftView> read = drafts.read(started.draftId());
        assertTrue(read.isEmpty(),
                "an expired draft must read as absent — the REST layer maps it to 404 "
                + "(the frozen contract declares no 410). Relying on the 5-minute purge cron alone "
                + "leaves an expired draft readable.");
    }

    @Test
    void updatedAtIsRefreshedOnEveryMutation() {
        DraftView started = drafts.start("PRUDENT", PRUDENT);
        Instant afterStart = persistedUpdatedAt(started.draftId());

        drafts.updateSubscriber(started.draftId(), subscriber());
        Instant afterUpdate = persistedUpdatedAt(started.draftId());

        assertFalse(afterUpdate.isBefore(afterStart),
                "updated_at must be refreshed on every mutation — plan 13 retention and plan 35 "
                + "GDPR evidence both read it");
    }

    @Test
    void retentionEqualsTheFormerSessionTimeout() {
        DraftView started = drafts.start("PRUDENT", PRUDENT);
        SubscriptionDraftEntity entity = find(started.draftId());
        long minutes = java.time.Duration.between(entity.createdAt, entity.expiresAt).toMinutes();
        assertEquals(20, minutes,
                "strict equivalence with <session-timeout>20</session-timeout> (BASELINE.md 4.2)");
    }

    UUID persistedKey(UUID draftId) {
        return find(draftId).confirmationKey;
    }

    Instant persistedUpdatedAt(UUID draftId) {
        return find(draftId).updatedAt;
    }

    void expire(UUID draftId) {
        QuarkusTransaction.requiringNew().run(() -> {
            SubscriptionDraftEntity entity = repository.findByIdOptional(draftId).orElseThrow();
            entity.expiresAt = Instant.now().minusSeconds(60);
            repository.persist(entity);
        });
    }

    SubscriptionDraftEntity find(UUID draftId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            repository.getEntityManager().clear();
            return repository.findByIdOptional(draftId)
                    .orElseThrow(() -> new AssertionError("draft row not found: " + draftId));
        });
    }

    private static Subscriber subscriber() {
        return new Subscriber("Claire", "Dupont", "claire.dupont@example.org",
                LocalDate.of(1990, 5, 4));
    }

    private static Simulation simulation() {
        return new Simulation(new BigDecimal("1000.00"), new BigDecimal("100.00"), 10,
                new BigDecimal("13000.00"), new BigDecimal("14321.55"));
    }
}
