package fr.demo.souscription.gateway.client;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Points the three REST clients at their WireMock double for the whole test run.
 *
 * <p>Registered globally through
 * {@code META-INF/services/io.quarkus.test.common.QuarkusTestResourceLifecycleManager} so every
 * {@code @QuarkusTest} sees the downstream contracts answered; the overrides replace the
 * {@code ${CATALOGUE_BASE_URI}} / {@code ${SIMULATION_BASE_URI}} /
 * {@code ${SUBSCRIPTION_BASE_URI}} references of {@code application.properties}.
 */
public class DownstreamWireMockResource implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        Map<String, String> overrides = new LinkedHashMap<>();
        DownstreamWireMocks.start().forEach((key, baseUri) ->
                overrides.put("quarkus.rest-client." + key + ".url", baseUri));
        return overrides;
    }

    @Override
    public void stop() {
        DownstreamWireMocks.stop();
    }
}
