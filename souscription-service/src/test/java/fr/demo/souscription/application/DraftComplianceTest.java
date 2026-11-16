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
 * W7-T07 : obligations RGPD creees par la persistance du brouillon (ecart CFG-011).
 *
 * <p>Trois preuves, correspondant aux sections 2, 4, 3 et 7 de {@code COMPLIANCE_RGPD.md} :
 * <ul>
 *   <li>la purge de retention ne supprime que les brouillons expires et ne touche jamais la table
 *       contractuelle {@code subscriptions.subscriptions}, dont la duree de conservation est
 *       differente ; la requete de controle du document renvoie 0 apres un cycle de purge ;</li>
 *   <li>{@code DraftRepository.deleteByDraftId} supprime exactement une ligne — c'est le point
 *       d'entree du droit a l'effacement, volontairement non expose en HTTP ;</li>
 *   <li>aucune ligne de journal emise pendant le traitement d'un brouillon ne contient
 *       l'adresse electronique, le nom, le prenom ou la date de naissance du souscripteur.</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class DraftComplianceTest {

    private static final Product PRUDENT =
            new Product("PRUDENT", "Horizon Prudent", "Prudent", new BigDecimal("0.020000"));

    private static final String FIRST_NAME = "Jeanne";
    private static final String LAST_NAME = "Delacroix-Vasseur";
    private static final String EMAIL = "jeanne.delacroix@example.com";
    private static final LocalDate BIRTH_DATE = LocalDate.of(1983, 7, 19);

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
    void purgeDeletesOnlyExpiredDraftsAndNeverTouchesTheContractualTable() {
        SubscriptionDraftEntity expired = SubscriptionDraftEntity.create(PRUDENT,
                clock.instant().minus(Duration.ofHours(2)), Duration.ofMinutes(20));
        SubscriptionDraftEntity active =
                SubscriptionDraftEntity.create(PRUDENT, clock.instant(), Duration.ofMinutes(20));
        SubscriptionEntity confirmed = confirmedSubscription();

        QuarkusTransaction.requiringNew().run(() -> {
            drafts.deleteAll();
            drafts.persist(expired);
            drafts.persist(active);
            subscriptions.persist(confirmed);
        });

        job.purgeExpiredDrafts();

        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.clear();
            assertTrue(drafts.findByIdOptional(expired.draftId).isEmpty(),
                    "le brouillon expire doit avoir ete supprime : la retention PT20M est la "
                            + "duree de conservation inscrite au registre des traitements");
            assertTrue(drafts.findByIdOptional(active.draftId).isPresent(),
                    "un brouillon encore valide doit subsister : la purge n'est pas une remise a "
                            + "zero du parcours en cours");
            assertTrue(subscriptions.findByIdOptional(confirmed.reference).isPresent(),
                    "la purge du brouillon ne doit jamais toucher subscriptions.subscriptions : "
                            + "finalite contractuelle, duree de conservation distincte");

            Number remaining = (Number) entityManager
                    .createNativeQuery("SELECT count(*) FROM subscriptions.subscription_draft"
                            + " WHERE expires_at < now()")
                    .getSingleResult();
            assertEquals(0L, remaining.longValue(),
                    "la requete de controle de COMPLIANCE_RGPD.md doit renvoyer 0 apres un cycle "
                            + "de purge : une purge configuree mais inoperante laisserait des "
                            + "donnees personnelles au-dela de leur duree de conservation");
        });

        QuarkusTransaction.requiringNew().run(() -> {
            drafts.deleteAll();
            subscriptions.deleteById(confirmed.reference);
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
                "l'effacement a la demande doit supprimer exactement la ligne designee");

        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.clear();
            assertTrue(drafts.findByIdOptional(target.draftId).isEmpty(),
                    "le brouillon efface a la demande ne doit plus exister");
            assertTrue(drafts.findByIdOptional(other.draftId).isPresent(),
                    "l'effacement par draftId ne doit pas affecter un autre brouillon");
        });

        assertEquals(0L, QuarkusTransaction.requiringNew()
                        .call(() -> drafts.deleteByDraftId(target.draftId)),
                "un second effacement du meme draftId doit etre sans effet");

        QuarkusTransaction.requiringNew().run(drafts::deleteAll);
    }

    @Test
    void noLogLineEmittedWhileHandlingADraftContainsPersonalData() {
        DraftView started = draftService.start("PRUDENT", PRUDENT);
        UUID draftId = started.draftId();
        draftService.updateSubscriber(draftId,
                new Subscriber(FIRST_NAME, LAST_NAME, EMAIL, BIRTH_DATE));
        draftService.read(draftId);

        assertFalse(captured.isEmpty(),
                "aucune ligne de journal n'a ete capturee : le test ne prouverait rien");

        io.quarkus.logging.json.runtime.JsonFormatter formatter =
                new io.quarkus.logging.json.runtime.JsonFormatter();
        boolean draftIdLogged = false;
        for (ExtLogRecord record : captured) {
            String line = formatter.format(record);
            assertFalse(line.contains(EMAIL), "adresse electronique journalisee : " + line);
            assertFalse(line.contains(LAST_NAME), "nom journalise : " + line);
            assertFalse(line.contains(FIRST_NAME), "prenom journalise : " + line);
            assertFalse(line.contains(BIRTH_DATE.toString()),
                    "date de naissance journalisee : " + line);
            draftIdLogged = draftIdLogged || line.contains(draftId.toString());
        }
        assertTrue(draftIdLogged,
                "un brouillon doit etre journalise par son draftId : c'est le substitut des "
                        + "donnees personnelles, pas leur complement");

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
        entity.initialAmount = new BigDecimal("1000.00");
        entity.monthlyAmount = new BigDecimal("100.00");
        entity.durationYears = 10;
        entity.contributed = new BigDecimal("13000.00");
        entity.finalCapital = new BigDecimal("14000.00");
        entity.createdAt = Instant.now();
        return entity;
    }
}
