package fr.demo.souscription.integration;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Demarre les bouchons des deux contrats consommes ({@link CatalogueStubServer},
 * {@link SimulationStubServer}) pour la duree de vie de l'application de test.
 *
 * <p>Les clients REST sont la seule implementation des contrats consommes : tout test qui ouvre
 * un brouillon ou confirme une souscription traverse les clients REST et exige donc ces bouchons.
 * Applique par {@code @WithTestResource} aux seules classes qui en ont besoin, afin de ne pas
 * entrer en conflit avec les tests qui pilotent eux-memes un bouchon sur le meme port fixe.
 */
public class ContractStubsResource implements QuarkusTestResourceLifecycleManager {

    private CatalogueStubServer catalogue;
    private SimulationStubServer simulation;

    @Override
    public Map<String, String> start() {
        try {
            catalogue = new CatalogueStubServer();
            simulation = new SimulationStubServer();
        } catch (IOException e) {
            stop();
            throw new UncheckedIOException(e);
        }
        return Map.of();
    }

    @Override
    public void stop() {
        if (catalogue != null) {
            catalogue.close();
            catalogue = null;
        }
        if (simulation != null) {
            simulation.close();
            simulation = null;
        }
    }
}
