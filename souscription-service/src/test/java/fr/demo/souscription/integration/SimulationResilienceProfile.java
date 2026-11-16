package fr.demo.souscription.integration;

import java.util.HashMap;
import java.util.Map;

/**
 * Profil des tests de resilience de la simulation : meme configuration distante que
 * {@link RemoteSimulationProfile}, classe distincte pour obtenir une application (et donc un
 * disjoncteur) propre, plus la base PostgreSQL de Dev Services — l'assertion « aucune ligne
 * partielle » exige un stockage reel — et la sonde {@link TransactionProbeFilter} sur le client
 * de simulation.
 */
public class SimulationResilienceProfile extends RemoteSimulationProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("quarkus.rest-client.simulation.providers",
                TransactionProbeFilter.class.getName());
        overrides.put("quarkus.datasource.active", "true");
        overrides.put("quarkus.hibernate-orm.active", "true");
        overrides.put("quarkus.datasource.devservices.enabled", "true");
        overrides.put("quarkus.datasource.devservices.image-name", "postgres:16-alpine");
        return overrides;
    }
}
