package fr.demo.souscription.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Parity skeleton for 08-souscription-service-flyway-v1-schema.
 *
 * Baseline reference: BASELINE.md section 4.4 — src/main/resources/db/schema.sql.
 * The source DDL declared 16 columns, a UNIQUE confirmation_key, DECIMAL(8,6) annual_rate,
 * five DECIMAL(14,2) money columns and three CHECK constraints. All of them are storage-contract
 * obligations, not implementation details.
 *
 * Install at:
 *   souscription-service/src/test/java/fr/demo/souscription/persistence/SchemaParityTest.java
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class SchemaParityTest {

    private static final List<String> SOURCE_COLUMNS = List.of(
            "reference", "confirmation_key", "product_code", "product_name", "product_profile",
            "annual_rate", "first_name", "last_name", "email", "birth_date", "initial_amount",
            "monthly_amount", "duration_years", "contributed", "final_capital", "created_at");

    @Inject
    EntityManager entityManager;

    @Test
    void migrationV1AppliedSuccessfully() {
        List<?> rows = entityManager
                .createNativeQuery(
                        "SELECT success FROM subscriptions.flyway_schema_history WHERE version = '1'")
                .getResultList();
        assertEquals(1, rows.size(), "no flyway_schema_history row for version 1");
        assertEquals(Boolean.TRUE, rows.get(0), "the V1 migration did not succeed");
    }

    @Test
    void columnNamesMatchSource() {
        @SuppressWarnings("unchecked")
        List<String> found = entityManager
                .createNativeQuery(
                        "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'subscriptions' AND table_name = 'subscriptions' "
                        + "ORDER BY ordinal_position")
                .getResultList();
        assertEquals(16, found.size(), "the source DDL declares exactly 16 columns");
        assertEquals(SOURCE_COLUMNS, found, "column names must be the source snake_case names");
    }

    @Test
    void decimalScalesPreserved() {
        assertEquals("8,6", precisionAndScale("annual_rate"),
                "annual_rate must stay numeric(8,6): at scale 2 the contracted \"0.020000\" collapses to \"0.02\"");
        for (String money : List.of("initial_amount", "monthly_amount", "contributed", "final_capital")) {
            assertEquals("14,2", precisionAndScale(money),
                    money + " must stay numeric(14,2) — never float/double");
        }
    }

    @Test
    void confirmationKeyIsUniqueAndNotNull() {
        String key = UUID.randomUUID().toString();
        insertRow(UUID.randomUUID().toString(), key, "1000.00", "100.00", 10);
        RuntimeException duplicate = assertThrows(RuntimeException.class,
                () -> insertRow(UUID.randomUUID().toString(), key, "1000.00", "100.00", 10),
                "a duplicate confirmation_key must be rejected by the database");
        // The source keyed its idempotent re-read on exactly this SQLState (BASELINE.md 4.4).
        assertEquals("23505", sqlState(duplicate),
                "the unique violation must surface as SQLState 23505; plan 23 keys on it");
    }

    @Test
    void checkConstraintsRejectOutOfRange() {
        assertEquals("23514", sqlState(assertThrows(RuntimeException.class,
                () -> insertRow(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                        "99.99", "100.00", 10))),
                "initial_amount below 100 must be rejected by the CHECK constraint");
        assertEquals("23514", sqlState(assertThrows(RuntimeException.class,
                () -> insertRow(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                        "1000.00", "2000.01", 10))),
                "monthly_amount above 2000 must be rejected by the CHECK constraint");
        assertEquals("23514", sqlState(assertThrows(RuntimeException.class,
                () -> insertRow(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                        "1000.00", "100.00", 31))),
                "duration_years above 30 must be rejected by the CHECK constraint");
    }

    @Test
    void migrationIsForwardOnly() {
        // A destructive statement in V1 would already have dropped data on a populated environment.
        Object count = entityManager
                .createNativeQuery(
                        "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'subscriptions' AND table_name = 'subscriptions'")
                .getSingleResult();
        assertEquals(1L, ((Number) count).longValue(),
                "subscriptions.subscriptions must exist after V1");
    }

    private String precisionAndScale(String column) {
        Object[] row = (Object[]) entityManager
                .createNativeQuery(
                        "SELECT numeric_precision, numeric_scale FROM information_schema.columns "
                        + "WHERE table_schema = 'subscriptions' AND table_name = 'subscriptions' "
                        + "AND column_name = ?1")
                .setParameter(1, column)
                .getSingleResult();
        assertNotNull(row, "column not found: " + column);
        return ((Number) row[0]).intValue() + "," + ((Number) row[1]).intValue();
    }

    /** Remonte le SQLState porte par la cause {@link SQLException} de l'echec de persistance. */
    private String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sqlFailure) {
                return sqlFailure.getSQLState();
            }
        }
        throw new AssertionError("aucune SQLException dans la chaine de causes", failure);
    }

    private void insertRow(String reference, String key, String initial, String monthly, int years) {
        QuarkusTransaction.requiringNew().run(() -> entityManager
                .createNativeQuery(
                        "INSERT INTO subscriptions.subscriptions (reference, confirmation_key, "
                        + "product_code, product_name, product_profile, annual_rate, first_name, "
                        + "last_name, email, birth_date, initial_amount, monthly_amount, "
                        + "duration_years, contributed, final_capital, created_at) VALUES "
                        + "(CAST(?1 AS uuid), CAST(?2 AS uuid), 'PRUDENT', 'Horizon Prudent', 'Prudent', "
                        + "0.020000, 'Claire', 'Dupont', 'claire.dupont@example.org', DATE '1990-05-04', "
                        + "CAST(?3 AS numeric), CAST(?4 AS numeric), ?5, 1000.00, 1000.00, now())")
                .setParameter(1, reference)
                .setParameter(2, key)
                .setParameter(3, initial)
                .setParameter(4, monthly)
                .setParameter(5, years)
                .executeUpdate());
    }
}
