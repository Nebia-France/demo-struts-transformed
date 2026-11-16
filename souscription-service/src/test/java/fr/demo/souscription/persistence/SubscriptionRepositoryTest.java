package fr.demo.souscription.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.domain.SubscriptionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Portage de src/test/java/fr/demo/souscription/repository/SubscriptionRepositoryTest.java
 * (H2 fichier + DAO JDBC) vers Panache et PostgreSQL Dev Services (W1-T05).
 *
 * <p>Les trois methodes du test source sont conservees. Les assertions sont adaptees a l'API
 * Panache ; la ou le DAO source absorbait la violation de cle unique, ce portage constate que
 * la base ne cree qu'une seule ligne : l'absorption cote applicatif est le perimetre du plan
 * 23-souscription-service-idempotent-confirm.
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class SubscriptionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    @Inject
    SubscriptionRepository repository;

    @Test
    void survivesClosingAndReopeningAndPreservesTheProductSnapshot() {
        UUID key = UUID.randomUUID();
        SubscriptionEntity saved = persistInNewTransaction(newEntity(key));

        // L'equivalent Panache de la reouverture de la base : le contexte de persistance est
        // vide, la lecture repart necessairement de PostgreSQL.
        SubscriptionEntity loaded = QuarkusTransaction.requiringNew()
                .call(() -> repository.findByKey(key).orElseThrow(AssertionError::new));

        assertEquals(saved.reference, loaded.reference);
        assertEquals("Horizon Prudent", loaded.productName);
        assertEquals(0, new BigDecimal("0.020000").compareTo(loaded.annualRate));
        assertEquals(0, new BigDecimal("14321.55").compareTo(loaded.finalCapital));
        assertEquals(NOW, loaded.createdAt.truncatedTo(ChronoUnit.MILLIS));
        assertEquals(LocalDate.of(1980, 1, 1), loaded.birthDate);
    }

    @Test
    void concurrentDuplicateConfirmationsCreateOnlyOneRow() throws Exception {
        UUID key = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<UUID>> jobs = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                jobs.add(() -> persistInNewTransaction(newEntity(key)).reference);
            }
            List<Future<UUID>> results = executor.invokeAll(jobs);
            int succeeded = 0;
            for (Future<UUID> result : results) {
                try {
                    assertNotNull(result.get());
                    succeeded++;
                } catch (Exception expected) {
                    // Violation de la contrainte unique sur confirmation_key : la base arbitre.
                }
            }
            assertEquals(1, succeeded, "une seule confirmation doit aboutir pour une cle donnee");
            assertEquals(1, countByKey(key), "la table ne doit contenir qu'une ligne pour cette cle");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedInsertDoesNotProduceAConfirmation() {
        UUID key = UUID.randomUUID();
        SubscriptionEntity invalid = newEntity(key);
        invalid.email = String.join("", Collections.nCopies(300, "x"));

        assertThrows(Exception.class, () -> persistInNewTransaction(invalid));
        assertFalse(QuarkusTransaction.requiringNew().call(() -> repository.findByKey(key).isPresent()),
                "un insert en echec ne doit laisser aucune confirmation lisible");
        assertNotNull(persistInNewTransaction(newEntity(key)));
    }

    @Test
    void representativeDatasetRoundTripsValueForValue() {
        UUID key = UUID.randomUUID();
        SubscriptionEntity written = persistInNewTransaction(newEntity(key));

        SubscriptionEntity read = QuarkusTransaction.requiringNew()
                .call(() -> repository.findByConfirmationKey(key).orElseThrow());

        assertTrue(read.reference != null);
        assertEquals(written.reference, read.reference);
        assertEquals(key, read.confirmationKey);
        assertEquals(0, new BigDecimal("1000.00").compareTo(read.initialAmount));
        assertEquals(2, read.initialAmount.scale(), "initial_amount doit revenir en echelle 2");
        assertEquals(0, new BigDecimal("100.00").compareTo(read.monthlyAmount));
        assertEquals(2, read.monthlyAmount.scale(), "monthly_amount doit revenir en echelle 2");
        assertEquals(LocalDate.of(1980, 1, 1), read.birthDate, "LocalDate ne doit pas deriver");
        assertEquals(NOW, read.createdAt.truncatedTo(ChronoUnit.MILLIS),
                "created_at doit revenir comme le meme Instant, sans decalage de fuseau");
    }

    private long countByKey(UUID key) {
        return QuarkusTransaction.requiringNew().call(() -> repository.count("confirmationKey", key));
    }

    private SubscriptionEntity persistInNewTransaction(SubscriptionEntity entity) {
        return QuarkusTransaction.requiringNew().call(() -> {
            repository.persist(entity);
            repository.getEntityManager().flush();
            return entity;
        });
    }

    private SubscriptionEntity newEntity(UUID key) {
        SubscriptionEntity entity = new SubscriptionEntity();
        entity.reference = UUID.randomUUID();
        entity.confirmationKey = key;
        entity.productCode = "PRUDENT";
        entity.productName = "Horizon Prudent";
        entity.productProfile = "Prudent";
        entity.annualRate = new BigDecimal("0.020000");
        entity.firstName = "Camille";
        entity.lastName = "Martin";
        entity.email = "camille@example.test";
        entity.birthDate = LocalDate.of(1980, 1, 1);
        entity.initialAmount = new BigDecimal("1000.00");
        entity.monthlyAmount = new BigDecimal("100.00");
        entity.durationYears = 5;
        entity.contributed = new BigDecimal("7000.00");
        entity.finalCapital = new BigDecimal("14321.55");
        entity.createdAt = NOW;
        return entity;
    }
}
