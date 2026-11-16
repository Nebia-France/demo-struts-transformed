package fr.demo.souscription.contract;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Profil des tests consommateurs pilotes par les contrats geles (plan 42) : les deux clients REST
 * sont diriges vers les bouchons {@link ContractStub} generes depuis les {@code openapi.yaml}
 * consommes. Ports distincts de ceux des bouchons manuels du plan 27/28 et instance Quarkus
 * propre, donc etat de disjoncteur independant des tests de resilience.
 */
public class ContractDrivenStubsProfile implements QuarkusTestProfile {

    static final int CATALOGUE_PORT = 18091;

    static final int SIMULATION_PORT = 18092;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.rest-client.catalogue.url", "http://localhost:" + CATALOGUE_PORT,
                "quarkus.rest-client.simulation.url", "http://localhost:" + SIMULATION_PORT);
    }
}
