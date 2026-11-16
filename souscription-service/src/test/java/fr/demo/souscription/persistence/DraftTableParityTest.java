package fr.demo.souscription.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Controle de parite fonctionnelle du plan 10 : le brouillon, jusqu'ici un objet Draft
 * marque pour la serialisation Java et porte par session HTTP de servlet (BASELINE.md 4.2, avec
 * {@code <session-timeout>20</session-timeout>} comme seule borne de duree de vie), devient une
 * ligne de subscriptions.subscription_draft.
 *
 * <p>Il n'existe aucun stockage source a comparer : les criteres sont donc les semantiques source
 * que la table doit reproduire — trois etats de parcours representables, un jeton non devinable,
 * une copie du produit, un point de controle de concurrence et une duree de vie bornee.
 *
 * <p>Additif : ne remplace pas {@code DraftTableMigrationTest}. Les requetes passent par
 * l'EntityManager, le service ne devant plus contenir de JDBC brut (TG-002).
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class DraftTableParityTest {

    @Inject
    EntityManager entityManager;

    @Test
    void migrationV2AppliedWithNineteenColumns() {
        List<?> applied = entityManager
                .createNativeQuery("SELECT success FROM subscriptions.flyway_schema_history"
                        + " WHERE version = '2'")
                .getResultList();
        assertEquals(1, applied.size(), "flyway_schema_history doit contenir la version 2");
        assertEquals(Boolean.TRUE, applied.get(0), "la migration V2 doit etre reussie");
        assertEquals(19, columnCount(), "subscription_draft doit declarer exactement 19 colonnes");
    }

    @Test
    void intermediateStatesAreRepresentable() {
        // Etat juste apres le `new Draft(product)` source : le produit seul.
        UUID draftId = insertProductOnlyDraft();

        Object[] row = (Object[]) QuarkusTransaction.requiringNew().call(() -> entityManager
                .createNativeQuery("SELECT first_name, last_name, email, birth_date,"
                        + " initial_amount, monthly_amount, duration_years, contributed,"
                        + " final_capital FROM subscriptions.subscription_draft"
                        + " WHERE draft_id = ?1")
                .setParameter(1, draftId)
                .getSingleResult());

        String[] columns = {"first_name", "last_name", "email", "birth_date", "initial_amount",
                "monthly_amount", "duration_years", "contributed", "final_capital"};
        for (int i = 0; i < columns.length; i++) {
            assertNull(row[i], columns[i] + " doit pouvoir rester NULL : le souscripteur et la"
                    + " simulation ne sont pas encore fournis a la creation du brouillon");
        }
        for (String column : columns) {
            assertEquals("YES", nullable(column), column + " doit etre nullable");
        }
        assertEquals(0, checkConstraintCount(),
                "la completude est une regle metier de SubscriptionService.confirm,"
                        + " pas une contrainte CHECK de la table brouillon");
    }

    @Test
    void draftIdIsAnOpaqueUuid() {
        assertEquals("uuid", columnType("draft_id"),
                "draft_id est le seul jeton porte par le SPA : un uuid, jamais une sequence");
        assertNull(columnDefault("draft_id"),
                "draft_id ne doit avoir aucun generateur cote base (pas de nextval) :"
                        + " l'application assigne UUID.randomUUID(), donc deux brouillons"
                        + " consecutifs ne sont pas enumerables (R-002)");

        UUID first = insertProductOnlyDraft();
        UUID second = insertProductOnlyDraft();
        assertNotEquals(first, second, "deux brouillons ne doivent pas collisionner");
        assertEquals(4, first.version(), "draft_id doit etre un UUID aleatoire de version 4");
    }

    @Test
    void productSnapshotIsCopiedWithNoForeignKey() {
        for (String column : List.of("product_code", "product_name", "product_profile",
                "annual_rate")) {
            assertNotNull(columnType(column), column + " doit etre copie dans le brouillon");
            assertEquals("NO", nullable(column), column + " doit etre NOT NULL");
        }
        Number foreignKeys = (Number) entityManager
                .createNativeQuery("SELECT count(*) FROM information_schema.table_constraints"
                        + " WHERE table_schema = 'subscriptions'"
                        + " AND table_name = 'subscription_draft'"
                        + " AND constraint_type = 'FOREIGN KEY'")
                .getSingleResult();
        assertEquals(0, foreignKeys.intValue(),
                "aucune cle etrangere : le catalogue est un service distant sans table dans ce"
                        + " schema, et la copie protege la projection vendue d'un changement"
                        + " ulterieur de taux");
    }

    @Test
    void versionColumnBacksTheOptimisticLock() {
        assertEquals("integer", columnType("version"),
                "version remplace le bloc de synchronisation de session source, consomme par le plan 23");
        assertEquals("NO", nullable("version"), "version doit etre NOT NULL");
        assertTrue(String.valueOf(columnDefault("version")).contains("0"),
                "version doit avoir DEFAULT 0 pour qu'un insert l'omettant reussisse");
        Number version = (Number) QuarkusTransaction.requiringNew().call(() -> entityManager
                .createNativeQuery("SELECT version FROM subscriptions.subscription_draft"
                        + " WHERE draft_id = ?1")
                .setParameter(1, insertProductOnlyDraft())
                .getSingleResult());
        assertEquals(0, version.intValue(), "un insert sans version doit rendre 0");
    }

    @Test
    void expiresAtIsNotNullIndexedAndZoneAware() {
        for (String column : List.of("created_at", "updated_at", "expires_at")) {
            assertEquals("timestamp with time zone", columnType(column),
                    column + " doit etre timestamptz : un timestamp naif ferait dependre"
                            + " l'expiration du fuseau de session de la replique qui purge");
            assertEquals("NO", nullable(column), column + " doit etre NOT NULL");
        }
        Number indexes = (Number) entityManager
                .createNativeQuery("SELECT count(*) FROM pg_indexes"
                        + " WHERE schemaname = 'subscriptions'"
                        + " AND indexname = 'idx_subscription_draft_expires_at'")
                .getSingleResult();
        assertEquals(1, indexes.intValue(),
                "la purge du plan 13 scanne expires_at a chaque tick");
    }

    private UUID insertProductOnlyDraft() {
        UUID draftId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> entityManager
                .createNativeQuery("INSERT INTO subscriptions.subscription_draft (draft_id,"
                        + " confirmation_key, product_code, product_name, product_profile,"
                        + " annual_rate, created_at, updated_at, expires_at)"
                        + " VALUES (?1, ?2, 'PRUDENT', 'Horizon Prudent', 'Prudent', 0.020000,"
                        + " now(), now(), now() + interval '20 minutes')")
                .setParameter(1, draftId)
                .setParameter(2, UUID.randomUUID())
                .executeUpdate());
        return draftId;
    }

    private int columnCount() {
        Number count = (Number) entityManager
                .createNativeQuery("SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_schema = 'subscriptions'"
                        + " AND table_name = 'subscription_draft'")
                .getSingleResult();
        return count.intValue();
    }

    private int checkConstraintCount() {
        Number count = (Number) entityManager
                .createNativeQuery("SELECT count(*) FROM information_schema.table_constraints"
                        + " WHERE table_schema = 'subscriptions'"
                        + " AND table_name = 'subscription_draft'"
                        + " AND constraint_type = 'CHECK'"
                        + " AND constraint_name NOT LIKE '%not_null%'")
                .getSingleResult();
        return count.intValue();
    }

    private String columnType(String column) {
        return metadata("data_type", column);
    }

    private String nullable(String column) {
        return metadata("is_nullable", column);
    }

    private String columnDefault(String column) {
        return metadata("column_default", column);
    }

    private String metadata(String field, String column) {
        return (String) entityManager
                .createNativeQuery("SELECT " + field + " FROM information_schema.columns"
                        + " WHERE table_schema = 'subscriptions'"
                        + " AND table_name = 'subscription_draft'"
                        + " AND column_name = ?1")
                .setParameter(1, column)
                .getSingleResult();
    }
}
