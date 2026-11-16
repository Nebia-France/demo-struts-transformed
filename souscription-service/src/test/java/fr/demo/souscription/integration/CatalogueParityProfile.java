package fr.demo.souscription.integration;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Profil du test de parite du plan 27 : meme configuration distante que
 * {@link RemoteCatalogueProfile}, classe distincte pour obtenir une application (et donc un
 * disjoncteur) propre, plus la sonde {@link TransactionProbeFilter} sur le client du catalogue.
 */
public class CatalogueParityProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.rest-client.catalogue.url",
                "http://localhost:" + CatalogueStubServer.PORT,
                "quarkus.rest-client.catalogue.providers",
                TransactionProbeFilter.class.getName());
    }
}
