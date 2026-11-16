package fr.demo.souscription.integration;

import java.util.HashMap;
import java.util.Map;

/**
 * Profil des assertions de resilience qui n'ont pas besoin de la base : meme configuration
 * distante que {@link RemoteSimulationProfile}, sans Dev Services PostgreSQL, plus la sonde
 * {@link TransactionProbeFilter} sur le client de simulation.
 *
 * <p>Classe de profil distincte : le disjoncteur est un etat partage par l'application, et les
 * assertions de reessai exigent un circuit ferme.
 */
public class SimulationResilienceOfflineProfile extends RemoteSimulationProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("quarkus.rest-client.simulation.providers",
                TransactionProbeFilter.class.getName());
        return overrides;
    }
}
