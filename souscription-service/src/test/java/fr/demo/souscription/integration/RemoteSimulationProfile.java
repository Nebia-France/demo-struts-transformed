package fr.demo.souscription.integration;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Profil de test du client REST de simulation (W6-T02) : {@link SimulationLookup} passe par le
 * client REST (seule implementation depuis le plan 41), pointe sur {@link SimulationStubServer}.
 *
 * <p>Par hermeticite, le client du catalogue est lui aussi
 * dirige vers son bouchon : un test qui n'a besoin que de la simulation ne demarre pas
 * {@link CatalogueStubServer}, mais aucune configuration ne pointe vers un fournisseur reel.
 */
public class RemoteSimulationProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.rest-client.simulation.url",
                "http://localhost:" + SimulationStubServer.PORT,
                "quarkus.rest-client.catalogue.url",
                "http://localhost:" + CatalogueStubServer.PORT);
    }
}
