package fr.demo.souscription.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "local.h2.enabled", matches = "true")
class LocalH2MigrationTest {

    @Test
    void existingMigrationsSupportH2AndPreserveSubscriptionTypes() throws Exception {
        Properties properties = new Properties();
        try (InputStream resource = getClass().getResourceAsStream("/application-local-h2.properties")) {
            properties.load(resource);
        }
        String url = properties.getProperty("quarkus.datasource.jdbc.url");
        // Base memoire jetable : le couple d'identifiants est genere ici et n'existe nulle part
        // dans le depot (NFG-004). H2 cree l'utilisateur a la premiere connexion.
        String username = "h2_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .schemas("subscriptions")
                .locations(properties.getProperty("quarkus.flyway.locations").split(","))
                .load();

        assertEquals(2, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);

        UUID reference = UUID.randomUUID();
        UUID confirmationKey = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-11-09T12:00:00Z");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            assertEquals("H2", connection.getMetaData().getDatabaseProductName());
            try (var insert = connection.prepareStatement("""
                    INSERT INTO subscriptions.subscriptions (
                        reference, confirmation_key, product_code, product_name, product_profile,
                        annual_rate, first_name, last_name, email, birth_date, initial_amount,
                        monthly_amount, duration_years, contributed, final_capital, created_at
                    ) VALUES (?, ?, 'PRUDENT', 'Horizon Prudent', 'Prudent',
                        0.02, 'Camille', 'Martin', 'camille@example.test', DATE '1980-01-01',
                        1000, 100, 5, 7000, 14321.55, ?)
                    """)) {
                insert.setObject(1, reference);
                insert.setObject(2, confirmationKey);
                insert.setObject(3, createdAt.atOffset(ZoneOffset.UTC));
                assertEquals(1, insert.executeUpdate());
            }
            try (var select = connection.prepareStatement("""
                    SELECT confirmation_key, created_at FROM subscriptions.subscriptions
                    WHERE reference = ?
                    """)) {
                select.setObject(1, reference);
                try (var result = select.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(confirmationKey, result.getObject("confirmation_key", UUID.class));
                    assertEquals(createdAt, result.getObject("created_at", OffsetDateTime.class).toInstant());
                }
            }
        }
    }
}