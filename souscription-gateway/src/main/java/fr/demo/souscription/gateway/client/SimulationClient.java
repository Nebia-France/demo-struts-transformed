package fr.demo.souscription.gateway.client;

import fr.demo.souscription.gateway.api.dto.SimulationInput;
import fr.demo.souscription.gateway.api.dto.SimulationResult;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Downstream view of souscription-simulation-service. The call stays remote, including on the
 * confirmation path (validated topology decision).
 *
 * <p>The path is read from
 * {@code global/contracts/souscription-gateway-to-souscription-simulation-service/openapi.yaml}
 * ({@code POST /v1/simulations}); the base URI and the timeouts come from
 * {@code quarkus.rest-client.simulation.*}.
 */
@Path("/v1/simulations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "simulation")
@RegisterProvider(DownstreamResponseExceptionMapper.class)
public interface SimulationClient {

    /**
     * The request carries the authoritative rate resolved from the catalogue; the simulation
     * service never reads the catalogue itself. Use
     * {@link SimulationComputeRequest#of(SimulationInput, java.math.BigDecimal)} to build it.
     */
    @POST
    Uni<SimulationResult> compute(SimulationComputeRequest request);
}
