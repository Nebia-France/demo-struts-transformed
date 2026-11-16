package fr.demo.souscription.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * W2-T03 / W2-T04 : le brouillon vit en base, et toute mutation regenere la cle de confirmation.
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class DraftServiceTest {

    private static final Product PRODUCT =
            new Product("PRUDENT", "Horizon Prudent", "Prudent", new BigDecimal("0.020000"));

    @Inject
    DraftService service;

    @Inject
    DraftRepository drafts;

    @Inject
    Clock clock;

    @Test
    void startPersistsADraftExpiringTwentyMinutesLater() {
        DraftView started = service.start("PRUDENT", PRODUCT);

        assertNotNull(started.draftId());
        assertNotNull(started.confirmationKey());
        assertEquals("STARTED", started.status());
        assertEquals("PRUDENT", started.product().code());

        SubscriptionDraftEntity row = QuarkusTransaction.requiringNew()
                .call(() -> drafts.findByIdOptional(started.draftId()).orElseThrow());
        assertEquals(Duration.ofMinutes(20),
                Duration.between(row.createdAt, row.expiresAt).truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void readReturnsThePersistedDraft() {
        DraftView started = service.start("PRUDENT", PRODUCT);

        DraftView read = service.read(started.draftId()).orElseThrow();

        assertEquals(started.draftId(), read.draftId());
        assertEquals(started.confirmationKey(), read.confirmationKey());
    }

    @Test
    void anExpiredDraftReadsAsEmpty() {
        SubscriptionDraftEntity expired = SubscriptionDraftEntity.create(PRODUCT,
                clock.instant().minus(Duration.ofHours(2)), Duration.ofMinutes(20));
        QuarkusTransaction.requiringNew().run(() -> drafts.persist(expired));

        assertTrue(service.read(expired.draftId).isEmpty(),
                "un brouillon dont expires_at est passe ne doit plus etre lisible");
    }

    @Test
    void eachUpdateRegeneratesTheConfirmationKey() {
        DraftView started = service.start("PRUDENT", PRODUCT);
        UUID draftId = started.draftId();

        DraftView withSubscriber = service.updateSubscriber(draftId,
                new Subscriber("Camille", "Martin", "camille@example.test",
                        LocalDate.of(1980, 1, 1)));
        assertNotEquals(started.confirmationKey(), withSubscriber.confirmationKey(),
                "la saisie du souscripteur doit invalider la cle de confirmation");
        assertEquals("SUBSCRIBER_CAPTURED", withSubscriber.status());
        assertFalse(service.isReadyForSummary(withSubscriber));

        DraftView withSimulation = service.updateSimulation(draftId,
                new Simulation(new BigDecimal("1000.00"), new BigDecimal("100.00"), 5,
                        new BigDecimal("7000.00"), new BigDecimal("14321.55")));
        assertNotEquals(withSubscriber.confirmationKey(), withSimulation.confirmationKey(),
                "la simulation doit invalider la cle de confirmation");
        assertEquals("SIMULATED", withSimulation.status());
        assertTrue(service.isReadyForSummary(withSimulation));
    }
}
