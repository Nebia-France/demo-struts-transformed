package fr.demo.souscription.application;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Profil de {@link ConfirmationServiceTest} : les deux clients REST distants sont declares
 * {@code jakarta.inject.Singleton} dans {@code application.properties}, portee que
 * {@code @InjectMock} ne peut pas remplacer. Ce profil les passe en portee normale
 * {@code ApplicationScoped} pour ce test uniquement ; la configuration de production est
 * inchangee.
 */
public class MockedRemoteClientsProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.rest-client.catalogue.scope",
                "jakarta.enterprise.context.ApplicationScoped",
                "quarkus.rest-client.simulation.scope",
                "jakarta.enterprise.context.ApplicationScoped");
    }
}
