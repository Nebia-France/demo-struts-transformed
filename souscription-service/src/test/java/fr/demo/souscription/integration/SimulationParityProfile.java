package fr.demo.souscription.integration;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Profil des tests de parite du plan 28 : les deux clients distants sont armes et diriges vers
 * leurs bouchons derives des contrats geles, et la sonde {@link TransactionProbeFilter} est posee
 * sur le client de <b>simulation</b> — c'est la seule facon de prouver, depuis l'interieur du
 * chemin d'appel, qu'aucune transaction n'est active pendant le recalcul (NFG-009).
 */
public class SimulationParityProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.rest-client.simulation.url",
                "http://localhost:" + SimulationStubServer.PORT,
                "quarkus.rest-client.catalogue.url",
                "http://localhost:" + CatalogueStubServer.PORT,
                "quarkus.rest-client.simulation.providers",
                TransactionProbeFilter.class.getName());
    }
}
