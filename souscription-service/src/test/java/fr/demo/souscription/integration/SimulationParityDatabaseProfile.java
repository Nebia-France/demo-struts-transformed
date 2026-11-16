package fr.demo.souscription.integration;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Profil du test de parite du plan 28 adosse a la base : le profil distant de
 * {@link SimulationParityProfile} plus Dev Services PostgreSQL, les criteres « valeur persistee »,
 * « aucune ligne apres panne » et « une seule tentative d'ecriture » exigeant une vraie ligne.
 */
public class SimulationParityDatabaseProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.rest-client.simulation.url",
                "http://localhost:" + SimulationStubServer.PORT,
                "quarkus.rest-client.catalogue.url",
                "http://localhost:" + CatalogueStubServer.PORT,
                "quarkus.rest-client.simulation.providers",
                TransactionProbeFilter.class.getName(),
                "quarkus.datasource.active", "true",
                "quarkus.hibernate-orm.active", "true",
                "quarkus.datasource.devservices.enabled", "true",
                "quarkus.datasource.devservices.image-name", "postgres:16-alpine");
    }
}
