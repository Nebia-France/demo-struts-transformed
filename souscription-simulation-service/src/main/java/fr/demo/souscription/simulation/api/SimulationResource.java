package fr.demo.souscription.simulation.api;

import fr.demo.souscription.simulation.model.Simulation;
import fr.demo.souscription.simulation.service.SimulationService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Remplace le traitement POST de l'action Struts : le calcul reste en process
 * (simulationService.calculate) mais le résultat est renvoyé en JSON, sans état serveur.
 * Le taux annuel est fourni par l'appelant.
 */
@Path("/v1/simulations")
@Tag(name = "simulations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SimulationResource {

    /** RFC 7807 media type des réponses d'erreur (global/contracts/shared-schemas/FieldErrors.json). */
    private static final String PROBLEM_JSON = "application/problem+json";

    private final SimulationService simulationService;
    private final SimulationInputDigestMeter inputDigestMeter;

    @Inject
    public SimulationResource(SimulationService simulationService,
                              SimulationInputDigestMeter inputDigestMeter) {
        this.simulationService = simulationService;
        this.inputDigestMeter = inputDigestMeter;
    }

    @POST
    @Blocking
    // Capacité bornée (W4-T03) : 20 appels concurrents, file d'attente de 40, conformément à
    // global/contracts/souscription-service-to-souscription-simulation-service/resilience-contract.json
    // (bulkhead.maxConcurrentCalls = 20, la valeur la plus stricte des deux contrats figés).
    // Au-delà, BulkheadException -> 503 problem+json (BulkheadExceptionMapper), jamais 500 ni 429.
    @Bulkhead(value = 20, waitingTaskQueue = 40)
    // 2 500 ms, strictement inférieur au timeout appelant de 3 000 ms des contrats figés : la
    // saturation produit un rejet serveur propre avant que l'appelant n'abandonne.
    @Timeout(value = 2500, unit = ChronoUnit.MILLIS)
    @Operation(operationId = "computeSimulation",
               summary = "Compute the projected capital",
               description = "Side-effect free. The annual rate is supplied by the caller, which obtained"
                       + " it from souscription-catalogue-service; this service never reads the catalogue.")
    @APIResponse(responseCode = "200",
                 description = "The projection.",
                 content = @Content(mediaType = MediaType.APPLICATION_JSON,
                                    schema = @Schema(implementation = SimulationResponse.class)))
    @APIResponse(responseCode = "400",
                 description = "Invalid amounts or duration. Field errors use the simulation field names"
                         + " (initialAmount, monthlyAmount, durationYears). Never retried by the caller.",
                 content = @Content(mediaType = PROBLEM_JSON,
                                    schema = @Schema(implementation = FieldErrors.class)))
    // 429 (rate limit, plan 26) and 503 (bulkhead/timeout) are deliberately NOT declared here:
    // the blocking openapi-contract-diff stage compares the generated response set with the frozen
    // contracts, which declare 200/400/500 only, and global/contracts/ is never edited to make the
    // gate pass. Both statuses are documented in CONTRACT.md and asserted by FaultInjectionTest.
    @APIResponse(responseCode = "500",
                 description = "Unexpected server error. Retried once by the caller.",
                 content = @Content(mediaType = PROBLEM_JSON,
                                    schema = @Schema(implementation = FieldErrors.class)))
    public SimulationResponse compute(
            @RequestBody(required = true,
                         content = @Content(mediaType = MediaType.APPLICATION_JSON,
                                            schema = @Schema(implementation = SimulationRequest.class)))
            @Valid SimulationRequest request,
            @Parameter(name = "X-Correlation-Id", in = ParameterIn.HEADER, required = false,
                       schema = @Schema(type = SchemaType.STRING, maxLength = 64))
            @HeaderParam("X-Correlation-Id") String correlationId,
            @Parameter(name = "traceparent", in = ParameterIn.HEADER, required = false,
                       schema = @Schema(type = SchemaType.STRING))
            @HeaderParam("traceparent") String traceparent) {
        // Instrumentation seule (W4-T05) : mesure du taux d'entrées dupliquées pour décider
        // d'un cache sur preuve. Aucun cache ici — le résultat est toujours recalculé.
        inputDigestMeter.record(request);
        // Le rejet du garde (IllegalArgumentException) est traduit en 400 problem+json par
        // IllegalArgumentExceptionMapper.
        Simulation simulation = simulationService.calculate(
                request.initialAmount(),
                request.monthlyAmount(),
                request.durationYears(),
                request.annualRate());
        return SimulationResponse.from(simulation);
    }
}
