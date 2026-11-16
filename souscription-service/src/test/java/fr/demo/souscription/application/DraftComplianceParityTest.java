package fr.demo.souscription.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.DraftView;
import fr.demo.souscription.domain.Product;
import fr.demo.souscription.domain.Subscriber;
import fr.demo.souscription.domain.SubscriptionDraftEntity;
import fr.demo.souscription.domain.SubscriptionEntity;
import fr.demo.souscription.persistence.RequiresContainerRuntime;
import fr.demo.souscription.persistence.DraftRepository;
import fr.demo.souscription.persistence.PostgresDevServicesProfile;
import fr.demo.souscription.persistence.SubscriptionRepository;
import io.quarkus.logging.json.runtime.JsonFormatter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parity check for 35-souscription-service-rgpd-draft-compliance — database half.
 *
 * <p>The migration created a personal-data store the source did not have (CFG-011): the monolith
 * held the subscriber's identity in {@code HttpSession} for 20 minutes at most, the target writes
 * it to {@code subscriptions.subscription_draft} for a person who has not yet subscribed. The
 * parity requirement is that the data is neither more exposed nor longer retained than the source
 * made it, which is what the four proofs below cover:
 *
 * <ul>
 *   <li>the retention is provably effective, not merely configured: the control query of
 *       {@code COMPLIANCE_RGPD.md} returns 0 after a purge cycle. A configured-but-ineffective
 *       purge is the failure mode with the longest time to detection;</li>
 *   <li>the purge never touches the contractual table, whose legal basis and retention period
 *       differ;</li>
 *   <li>{@code deleteByDraftId} removes exactly one row — an over-broad delete executed during an
 *       erasure request would destroy unrelated users' data;</li>
 *   <li>no log line emitted while handling a draft carries personal data. Log stores are retained
 *       longer and read by more people than the database, so a leak there defeats {@code PT20M}.
 *       </li>
 * </ul>
 *
 * <p>Adapted to this module's harness: the PostgreSQL Dev Services profile and the container-runtime
 * guard used by every other persistence test, and {@code QuarkusTransaction} rather than
 * {@code @Transactional} helpers (self-invocation in a test class is not intercepted). The
 * assertions that need no database are in {@code DraftCompliancePolicyParityTest}, so they run on
 * every build.
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class DraftComplianceParityTest {

    private static final Product PRUDENT =
            new Product("PRUDENT", "Horizon Prudent", "Prudent", new BigDecimal("0.020000"));

    private static final String FIRST_NAME = "Aurelien";
    private static final String LAST_NAME = "Montrachet-Laborde";
    private static final String EMAIL = "aurelien.montrachet@example.org";
    private static final LocalDate BIRTH_DATE = LocalDate.of(1979, 2, 23);

    @Inject
    DraftService draftService;

    @Inject
    DraftRetentionJob job;

    @Inject
    DraftRepository drafts;

    @Inject
    SubscriptionRepository subscriptions;

    @Inject
    EntityManager entityManager;

    @Inject
    Clock clock;

    private final List<ExtLogRecord> captured = new ArrayList<>();

    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record instanceof ExtLogRecord extended) {
                extended.copyMdc();
                captured.add(extended);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private Logger moduleLogger;

    @BeforeEach
    void installHandler() {
        captured.clear();
        moduleLogger = Logger.getLogger("fr.demo.souscription");
        moduleLogger.setLevel(Level.ALL);
        moduleLogger.addHandler(handler);
    }

    @AfterEach
    void removeHandler() {
        moduleLogger.removeHandler(handler);
    }

    @Test
    void noExpiredDraftRemainsAfterAPurgeCycle() {
        SubscriptionDraftEntity expired = SubscriptionDraftEntity.create(PRUDENT,
                clock.instant().minus(Duration.ofHours(3)), Duration.ofMinutes(20));
        SubscriptionDraftEntity alsoExpired = SubscriptionDraftEntity.create(PRUDENT,
                clock.instant().minus(Duration.ofMinutes(21)), Duration.ofMinutes(20));

        QuarkusTransaction.requiringNew().run(() -> {
            drafts.deleteAll();
            drafts.persist(expired);
            drafts.persist(alsoExpired);
        });

        job.purgeExpiredDrafts();

        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.clear();
            Number remaining = (Number) entityManager
                    .createNativeQuery("SELECT count(*) FROM subscriptions.subscription_draft"
                            + " WHERE expires_at < now()")
                    .getSingleResult();
            assertEquals(0L, remaining.longValue(),
                    "the control query must return 0 after a purge cycle: a configured but "
                            + "ineffective purge voids the PT20M retention promise and the store "
                            + "accumulates personal data indefinitely");
            drafts.deleteAll();
        });
    }

    @Test
    void purgeNeverTouchesTheContractualTable() {
        SubscriptionDraftEntity expired = SubscriptionDraftEntity.create(PRUDENT,
                clock.instant().minus(Duration.ofHours(1)), Duration.ofMinutes(20));
        SubscriptionEntity confirmed = confirmedSubscription();

        long before = QuarkusTransaction.requiringNew().call(() -> {
            drafts.deleteAll();
            drafts.persist(expired);
            subscriptions.persist(confirmed);
            return subscriptions.count();
        });

        job.purgeExpiredDrafts();

        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.clear();
            assertEquals(before, subscriptions.count(),
                    "the contractual row count must be unchanged: applying the 20-minute draft "
                            + "policy to subscriptions.subscriptions would delete confirmed "
                            + "subscriptions, an unrecoverable loss of contractual records");
            assertTrue(subscriptions.findByIdOptional(confirmed.reference).isPresent(),
                    "the confirmed subscription must survive the draft purge");
            subscriptions.deleteById(confirmed.reference);
            drafts.deleteAll();
        });
    }

    @Test
    void deleteByDraftIdRemovesExactlyOneRow() {
        SubscriptionDraftEntity target =
                SubscriptionDraftEntity.create(PRUDENT, clock.instant(), Duration.ofMinutes(20));
        SubscriptionDraftEntity other =
                SubscriptionDraftEntity.create(PRUDENT, clock.instant(), Duration.ofMinutes(20));

        QuarkusTransaction.requiringNew().run(() -> {
            drafts.deleteAll();
            drafts.persist(target);
            drafts.persist(other);
        });

        long deleted = QuarkusTransaction.requiringNew()
                .call(() -> drafts.deleteByDraftId(target.draftId));

        assertEquals(1L, deleted,
                "erasure must be scoped to the primary key: an over-broad delete executed during "
                        + "an erasure request destroys unrelated users' data");

        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.clear();
            assertTrue(drafts.findByIdOptional(target.draftId).isEmpty(),
                    "the erased draft must be gone");
            assertTrue(drafts.findByIdOptional(other.draftId).isPresent(),
                    "another user's draft must be untouched");
            drafts.deleteAll();
        });
    }

    @Test
    void noPersonalDataInDraftLogs() {
        DraftView started = draftService.start("PRUDENT", PRUDENT);
        UUID draftId = started.draftId();
        draftService.updateSubscriber(draftId,
                new Subscriber(FIRST_NAME, LAST_NAME, EMAIL, BIRTH_DATE));
        draftService.read(draftId);

        assertFalse(captured.isEmpty(),
                "no log record was captured: the test would prove nothing");

        JsonFormatter formatter = new JsonFormatter();
        boolean draftIdLogged = false;
        for (ExtLogRecord record : captured) {
            String line = formatter.format(record);
            assertFalse(line.contains(EMAIL), "email address logged: " + line);
            assertFalse(line.contains(LAST_NAME), "last name logged: " + line);
            assertFalse(line.contains(FIRST_NAME), "first name logged: " + line);
            assertFalse(line.contains(BIRTH_DATE.toString()), "birth date logged: " + line);
            draftIdLogged = draftIdLogged || line.contains(draftId.toString());
        }
        assertTrue(draftIdLogged,
                "a draft must be logged by its draftId: the opaque token is the substitute for "
                        + "the personal data, not its complement");

        QuarkusTransaction.requiringNew().run(() -> drafts.deleteByDraftId(draftId));
    }

    private static SubscriptionEntity confirmedSubscription() {
        SubscriptionEntity entity = new SubscriptionEntity();
        entity.reference = UUID.randomUUID();
        entity.confirmationKey = UUID.randomUUID();
        entity.productCode = PRUDENT.code();
        entity.productName = PRUDENT.name();
        entity.productProfile = PRUDENT.profile();
        entity.annualRate = PRUDENT.annualRate();
        entity.firstName = FIRST_NAME;
        entity.lastName = LAST_NAME;
        entity.email = EMAIL;
        entity.birthDate = BIRTH_DATE;
        entity.initialAmount = new BigDecimal("2000.00");
        entity.monthlyAmount = new BigDecimal("150.00");
        entity.durationYears = 12;
        entity.contributed = new BigDecimal("23600.00");
        entity.finalCapital = new BigDecimal("25000.00");
        entity.createdAt = Instant.now();
        return entity;
    }
}
