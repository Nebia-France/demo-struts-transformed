package fr.demo.souscription.contract;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Profil du parcours de bout en bout contre les services soeurs <b>reels</b> (plan 42, etape 4).
 *
 * <p>Les adresses viennent de l'environnement, jamais d'une valeur en dur :
 * {@code HORIZON_E2E_CATALOGUE_URL} et {@code HORIZON_E2E_SIMULATION_URL} (par exemple
 * {@code https://souscription-catalogue-service.dev.svc.cluster.local:8443}). Le profil n'est
 * active que lorsque ces deux variables sont presentes (cf. {@link RealSiblingsJourneyTest}).
 */
public class RealSiblingsProfile implements QuarkusTestProfile {

    static final String CATALOGUE_URL = "HORIZON_E2E_CATALOGUE_URL";

    static final String SIMULATION_URL = "HORIZON_E2E_SIMULATION_URL";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.rest-client.catalogue.url", System.getenv().getOrDefault(CATALOGUE_URL, ""),
                "quarkus.rest-client.simulation.url", System.getenv().getOrDefault(SIMULATION_URL, ""));
    }
}
