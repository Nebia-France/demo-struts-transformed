package fr.demo.souscription.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import org.junit.jupiter.api.Test;

/**
 * Preuve de cablage du pool Agroal et de Dev Services PostgreSQL (W1-T01 / W1-T02) :
 * une connexion est empruntee au pool, une requete triviale est executee et le produit
 * JDBC est verifie. Aucun schema applicatif n'est requis.
 *
 * <p>Ce test exige un daemon de conteneurs (Docker / Podman) pour que Dev Services demarre
 * la base. Il est ignore via {@code @RequiresContainerRuntime} lorsque aucun
 * daemon n'est joignable, afin que {@code mvn clean test} reste vert sur un poste sans Docker.
 */
@QuarkusTest
@TestProfile(PostgresDevServicesProfile.class)
@RequiresContainerRuntime
class DatasourceSmokeTest {

    @Inject
    AgroalDataSource dataSource;

    @Inject
    EntityManager entityManager;

    @Test
    void poolProvidesAPostgresqlConnection() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(
                    "PostgreSQL",
                    connection.getMetaData().getDatabaseProductName(),
                    "le pool doit servir des connexions PostgreSQL");
        }

        Object one = entityManager.createNativeQuery("SELECT 1").getSingleResult();
        assertEquals(1, ((Number) one).intValue(), "SELECT 1 doit renvoyer 1");
    }
}
