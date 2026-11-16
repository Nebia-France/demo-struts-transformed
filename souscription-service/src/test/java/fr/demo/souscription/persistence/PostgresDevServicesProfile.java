package fr.demo.souscription.persistence;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Reactive le pool JDBC pour les tests qui ont reellement besoin de la base : le profil test
 * global laisse le pool desactive afin que les tests sans persistance n'exigent pas de daemon
 * de conteneurs. La base est fournie par Dev Services PostgreSQL.
 */
public class PostgresDevServicesProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.datasource.active", "true",
                "quarkus.hibernate-orm.active", "true",
                "quarkus.datasource.devservices.enabled", "true",
                "quarkus.datasource.devservices.image-name", "postgres:16-alpine");
    }
}
