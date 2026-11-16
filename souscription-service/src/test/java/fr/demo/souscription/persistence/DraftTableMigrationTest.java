package fr.demo.souscription.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Preuve que la migration Flyway V2 cree la table subscriptions.subscription_draft (W2-T01) :
 * le brouillon anciennement porte par session HTTP de servlet est desormais une ligne persistee et expirante.
 *
 * <p>Comme les autres tests de persistance, ce test exige un daemon de conteneurs pour que
 * Dev Services demarre PostgreSQL ; il est ignore sinon.
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class DraftTableMigrationTest {

    @Inject
    EntityManager entityManager;

    @Test
    void version2IsAppliedSuccessfully() {
        List<?> rows = entityManager
                .createNativeQuery(
                        "SELECT success FROM subscriptions.flyway_schema_history"
                                + " WHERE version = '2'")
                .getResultList();
        assertEquals(1, rows.size(), "flyway_schema_history doit contenir la version 2");
        assertEquals(Boolean.TRUE, rows.get(0), "la migration V2 doit etre reussie");
    }

    @Test
    void draftTableDeclaresNineteenColumns() {
        @SuppressWarnings("unchecked")
        List<String> found = entityManager
                .createNativeQuery(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_schema = 'subscriptions'"
                                + " AND table_name = 'subscription_draft'"
                                + " ORDER BY ordinal_position")
                .getResultList();
        assertEquals(19, found.size(), "subscription_draft doit declarer 19 colonnes : " + found);
        assertEquals(
                List.of("draft_id", "confirmation_key", "product_code", "product_name",
                        "product_profile", "annual_rate", "first_name", "last_name", "email",
                        "birth_date", "initial_amount", "monthly_amount", "duration_years",
                        "contributed", "final_capital", "version", "created_at", "updated_at",
                        "expires_at"),
                found,
                "les noms de colonnes doivent etre ceux du contrat de stockage");
    }

    @Test
    void intermediateJourneyStatesStayNullable() {
        for (String column : List.of("first_name", "email", "initial_amount")) {
            assertEquals("YES", isNullable(column),
                    column + " doit rester nullable : le parcours a des etats intermediaires"
                            + " ou le souscripteur et la simulation sont absents");
        }
        for (String column : List.of("draft_id", "confirmation_key", "expires_at")) {
            assertEquals("NO", isNullable(column), column + " doit etre NOT NULL");
        }
    }

    @Test
    void expiresAtIsIndexedForRetentionPurge() {
        @SuppressWarnings("unchecked")
        List<String> indexes = entityManager
                .createNativeQuery(
                        "SELECT indexname FROM pg_indexes"
                                + " WHERE schemaname = 'subscriptions'"
                                + " AND tablename = 'subscription_draft'")
                .getResultList();
        assertTrue(indexes.contains("idx_subscription_draft_expires_at"),
                "l'index sur expires_at est requis par la purge de retention ; trouve : "
                        + indexes);
    }

    private String isNullable(String column) {
        return (String) entityManager
                .createNativeQuery(
                        "SELECT is_nullable FROM information_schema.columns"
                                + " WHERE table_schema = 'subscriptions'"
                                + " AND table_name = 'subscription_draft'"
                                + " AND column_name = ?1")
                .setParameter(1, column)
                .getSingleResult();
    }
}
