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
 * Preuve que la migration Flyway V1 est l'unique source du schema (W1-T03) : la table
 * d'historique Flyway contient une ligne reussie pour la version 1 et les trois contraintes
 * CHECK du DDL d'origine sont presentes dans le schema subscriptions.
 *
 * <p>Comme {@code DatasourceSmokeTest}, ce test exige un daemon de conteneurs pour que
 * Dev Services demarre PostgreSQL ; il est ignore sinon.
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class FlywayMigrationTest {

    @Inject
    EntityManager entityManager;

    @Test
    void version1IsAppliedSuccessfully() {
        List<?> rows = entityManager
                .createNativeQuery(
                        "SELECT success FROM subscriptions.flyway_schema_history"
                                + " WHERE version = '1'")
                .getResultList();
        assertEquals(1, rows.size(), "flyway_schema_history doit contenir la version 1");
        assertEquals(Boolean.TRUE, rows.get(0), "la migration V1 doit etre reussie");
    }

    @Test
    void checkConstraintsOfTheSourceDdlArePreserved() {
        @SuppressWarnings("unchecked")
        List<String> allClauses = entityManager
                .createNativeQuery(
                        "SELECT cc.check_clause"
                                + " FROM information_schema.check_constraints cc"
                                + " JOIN information_schema.table_constraints tc"
                                + "   ON tc.constraint_name = cc.constraint_name"
                                + "  AND tc.constraint_schema = cc.constraint_schema"
                                + " WHERE tc.table_schema = 'subscriptions'"
                                + "   AND tc.table_name = 'subscriptions'")
                .getResultList();

        // information_schema expose aussi les NOT NULL comme contraintes CHECK :
        // seules les bornes de domaine du DDL d'origine sont verifiees ici.
        List<String> clauses = allClauses.stream()
                .filter(clause -> clause != null && !clause.endsWith("IS NOT NULL"))
                .toList();

        for (String column : List.of("initial_amount", "monthly_amount", "duration_years")) {
            assertTrue(
                    clauses.stream().anyMatch(clause -> clause.contains(column)),
                    "une contrainte CHECK doit porter sur " + column + " ; trouve : " + clauses);
        }
        assertTrue(clauses.size() >= 3, "au moins trois contraintes CHECK sont attendues");
    }
}
