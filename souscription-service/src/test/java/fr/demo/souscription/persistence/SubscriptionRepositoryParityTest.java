package fr.demo.souscription.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.domain.SubscriptionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Controle de parite fonctionnelle du plan 09 : la meme ecriture suivie de la meme lecture doit
 * rendre les memes valeurs via le repository Panache sur PostgreSQL que via le DAO JDBC source sur
 * H2 (BASELINE.md 4.4).
 *
 * <p>Les trois risques de parite couverts sont l'ecriture explicite des 16 colonnes, les echelles
 * decimales (6 pour annual_rate, 2 pour les quatre montants, que le DAO source relisait en
 * BigDecimal puis rendait via toPlainString()) et created_at ecrit depuis un Instant, qui ne doit
 * subir aucun decalage de fuseau.
 *
 * <p>Additif : ne remplace pas le portage {@code SubscriptionRepositoryTest}.
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class SubscriptionRepositoryParityTest {

    /** Valeur non ronde, representable en microsecondes, la precision de timestamptz. */
    private static final Instant CREATED_AT = Instant.parse("2026-11-07T23:45:12.123456Z");

    private static final LocalDate BIRTH_DATE = LocalDate.of(1980, 2, 29);

    @Inject
    SubscriptionRepository repository;

    @Test
    void roundTripPreservesEveryValue() {
        UUID key = UUID.randomUUID();
        SubscriptionEntity written = newEntity(key);
        UUID reference = written.reference;
        persistInNewTransaction(written);

        SubscriptionEntity read = readByConfirmationKey(key);
        assertNotSame(written, read, "la lecture doit repartir de PostgreSQL, pas du contexte");

        assertEquals(reference, read.reference, "reference assignee par l'application");
        assertEquals(key, read.confirmationKey);
        assertEquals("PRUDENT", read.productCode);
        assertEquals("Horizon Prudent", read.productName);
        assertEquals("Prudent", read.productProfile);
        assertEquals("Camille", read.firstName);
        assertEquals("Martin", read.lastName);
        assertEquals("camille@example.test", read.email);
        assertEquals(BIRTH_DATE, read.birthDate, "birth_date doit revenir comme le meme LocalDate");
        assertEquals(5, read.durationYears);
        assertEquals(0, new BigDecimal("0.020000").compareTo(read.annualRate));
        assertEquals(0, new BigDecimal("1000.00").compareTo(read.initialAmount));
        assertEquals(0, new BigDecimal("100.00").compareTo(read.monthlyAmount));
        assertEquals(0, new BigDecimal("7000.00").compareTo(read.contributed));
        assertEquals(0, new BigDecimal("14321.55").compareTo(read.finalCapital));
        assertEquals(CREATED_AT, read.createdAt);
    }

    @Test
    void decimalScalesSurviveTheRoundTrip() {
        UUID key = UUID.randomUUID();
        persistInNewTransaction(newEntity(key));

        SubscriptionEntity read = readByConfirmationKey(key);

        assertDecimal("0.020000", 6, read.annualRate, "annual_rate");
        assertDecimal("1000.00", 2, read.initialAmount, "initial_amount");
        assertDecimal("100.00", 2, read.monthlyAmount, "monthly_amount");
        assertDecimal("7000.00", 2, read.contributed, "contributed");
        assertDecimal("14321.55", 2, read.finalCapital, "final_capital");
    }

    @Test
    void instantIsStoredWithoutTimezoneShift() {
        UUID key = UUID.randomUUID();
        persistInNewTransaction(newEntity(key));

        SubscriptionEntity read = readByConfirmationKey(key);

        assertEquals(CREATED_AT, read.createdAt,
                "created_at doit revenir comme le meme Instant, sans decalage de fuseau");
        assertEquals(CREATED_AT.toEpochMilli(), read.createdAt.toEpochMilli());
        // Un decalage d'une ou deux heures signalerait un mapping LocalDateTime ou une colonne
        // timestamp sans fuseau : on le constate explicitement sur un fuseau non UTC.
        ZonedDateTime paris = read.createdAt.atZone(ZoneId.of("Europe/Paris"));
        assertEquals(CREATED_AT, paris.toInstant());
    }

    @Test
    void sourcePublicReadContractIsPreserved() {
        UUID key = UUID.randomUUID();
        UUID reference = persistInNewTransaction(newEntity(key)).reference;

        Optional<SubscriptionEntity> byKey =
                QuarkusTransaction.requiringNew().call(() -> repository.findByKey(key));

        assertTrue(byKey.isPresent(), "findByKey est le contrat de lecture public du DAO source");
        assertEquals(reference, byKey.orElseThrow().reference);
        assertTrue(QuarkusTransaction.requiringNew()
                        .call(() -> repository.findByKey(UUID.randomUUID()).isEmpty()),
                "une cle inconnue doit rendre un Optional vide, comme le DAO source");
    }

    private static void assertDecimal(String expected, int scale, BigDecimal actual, String column) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), column + " doit garder sa valeur");
        assertEquals(scale, actual.scale(), column + " doit revenir en echelle " + scale);
        assertEquals(expected, actual.toPlainString(),
                column + " doit rendre la meme chaine que toPlainString() cote source");
    }

    private SubscriptionEntity readByConfirmationKey(UUID key) {
        return QuarkusTransaction.requiringNew()
                .call(() -> repository.findByConfirmationKey(key).orElseThrow(AssertionError::new));
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
        entity.birthDate = BIRTH_DATE;
        entity.initialAmount = new BigDecimal("1000.00");
        entity.monthlyAmount = new BigDecimal("100.00");
        entity.durationYears = 5;
        entity.contributed = new BigDecimal("7000.00");
        entity.finalCapital = new BigDecimal("14321.55");
        entity.createdAt = CREATED_AT;
        return entity;
    }
}
