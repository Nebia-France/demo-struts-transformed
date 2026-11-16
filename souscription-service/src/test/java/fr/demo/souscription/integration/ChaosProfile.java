package fr.demo.souscription.integration;

import java.util.HashMap;
import java.util.Map;

/**
 * Profil du test de chaos (W6-T06) : clients REST distants pointes sur les bouchons locaux
 * ({@link RemoteSimulationProfile}) plus la base PostgreSQL de Dev Services, l'assertion
 * « aucune souscription partielle » exigeant un stockage reel.
 *
 * <p>Classe de profil distincte de {@link SimulationResilienceProfile} : le disjoncteur est un
 * etat partage par l'application, et le test de chaos doit demarrer sur un circuit ferme.
 */
public class ChaosProfile extends RemoteSimulationProfile {

    /**
     * Variante sans base : les quatre memes perturbations sont mesurees sur les appels sortants
     * seuls, sans daemon de conteneurs. Classe distincte pour obtenir une application — et donc
     * des disjoncteurs — propres.
     */
    public static class Offline extends RemoteSimulationProfile {
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("quarkus.datasource.active", "true");
        overrides.put("quarkus.hibernate-orm.active", "true");
        overrides.put("quarkus.datasource.devservices.enabled", "true");
        overrides.put("quarkus.datasource.devservices.image-name", "postgres:16-alpine");
        return overrides;
    }
}
