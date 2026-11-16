package fr.demo.souscription.gateway.api;

import fr.demo.souscription.gateway.api.dto.SimulationRequest;
import fr.demo.souscription.gateway.api.dto.SimulationResult;
import fr.demo.souscription.gateway.client.CatalogueGateway;
import fr.demo.souscription.gateway.client.SimulationGateway;
import fr.demo.souscription.gateway.client.SimulationComputeRequest;
import fr.demo.souscription.gateway.error.Problem;
import fr.demo.souscription.gateway.observability.Journeys;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Stateless projection, replacing the {@code calculer} command of the legacy simulation action.
 *
 * <p>The gateway resolves the product's authoritative annual rate from the catalogue and forwards
 * the computation to souscription-simulation-service; the client never supplies a rate. The call
 * stays remote (validated topology decision).
 */
@Path("/api/v1/simulations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "simulation", description = "Compound-interest projection, proxied to souscription-simulation-service.")
public class SimulationResource {

    private final CatalogueGateway catalogue;
    private final SimulationGateway simulation;

    @Inject
    public SimulationResource(CatalogueGateway catalogue,
                              SimulationGateway simulation) {
        this.catalogue = catalogue;
        this.simulation = simulation;
    }

    @POST
    @Operation(operationId = "simulate",
            summary = "Project the capital for a product and a set of amounts")
    @SecurityRequirement(name = "csrfToken")
    @APIResponse(responseCode = "200", description = "The projection.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = SimulationResult.class)))
    @APIResponse(responseCode = "400", description = "Validation structurelle en echec (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "403", description = "Jeton CSRF absent ou invalide (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "404", description = "Code produit inconnu (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "405", description = "Methode HTTP non autorisee (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "500", description = "Erreur interne (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "503", description = "Service aval indisponible (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "502", description = "Service aval en erreur (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "504", description = "Delai d'attente aval depasse (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @WithSpan("simulation-journey")
    public Uni<SimulationResult> simulate(@Valid SimulationRequest request) {
        Journeys.tag(Journeys.SIMULATION);
        return catalogue.detail(request.productCode())
                .flatMap(product -> simulation.compute(
                        SimulationComputeRequest.of(request.toInput(), product.annualRate())));
    }
}
