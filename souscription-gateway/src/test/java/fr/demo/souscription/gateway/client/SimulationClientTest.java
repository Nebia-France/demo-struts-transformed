package fr.demo.souscription.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.demo.souscription.gateway.api.dto.SimulationInput;
import fr.demo.souscription.gateway.api.dto.SimulationResult;
import io.quarkus.test.junit.QuarkusTest;
import java.math.BigDecimal;
import java.time.Duration;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

/**
 * Path, status and DTO shape of {@link SimulationClient} against the WireMock double of
 * {@code souscription-gateway-to-souscription-simulation-service} (W4-T01). The stub only answers
 * when the four contract-required members are present, so a missing {@code annualRate} fails here
 * rather than against the real provider.
 */
@QuarkusTest
class SimulationClientTest {

    @RestClient
    SimulationClient simulation;

    @Test
    void computePostsTheContractRequestAndDeserialisesTheProjection() {
        SimulationResult result = simulation.compute(SimulationComputeRequest.of(
                        new SimulationInput(new BigDecimal("1000.00"), new BigDecimal("100.00"), 10),
                        new BigDecimal("0.020000")))
                .await().atMost(Duration.ofSeconds(5));

        assertEquals(10, result.durationYears());
        assertEquals(0, new BigDecimal("13000.00").compareTo(result.contributed()));
        assertEquals(0, new BigDecimal("14963.21").compareTo(result.finalCapital()));
        DownstreamWireMocks.server("simulation")
                .verify(postRequestedFor(urlPathEqualTo("/v1/simulations")));
    }
}
