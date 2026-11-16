package fr.demo.souscription.integration;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Profil de test du client REST distant : {@link CatalogueLookup} resout le produit par le
 * client REST (seule implementation depuis le plan 41), pointe sur {@link CatalogueStubServer}.
 */
public class RemoteCatalogueProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.rest-client.catalogue.url", "http://localhost:" + CatalogueStubServer.PORT);
    }
}
