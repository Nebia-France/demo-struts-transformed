package fr.demo.souscription.gateway.api;

import fr.demo.souscription.gateway.api.dto.ConfirmationRequest;
import fr.demo.souscription.gateway.api.dto.DraftStartRequest;
import fr.demo.souscription.gateway.api.dto.DraftView;
import fr.demo.souscription.gateway.api.dto.ProductDetail;
import fr.demo.souscription.gateway.api.dto.SimulationInput;
import fr.demo.souscription.gateway.api.dto.SubscriberInput;
import fr.demo.souscription.gateway.api.dto.SubscriptionConfirmation;
import fr.demo.souscription.gateway.client.CatalogueGateway;
import fr.demo.souscription.gateway.client.ConfirmationOutcome;
import fr.demo.souscription.gateway.client.SubscriptionGateway;
import fr.demo.souscription.gateway.error.Problem;
import fr.demo.souscription.gateway.observability.Journeys;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Subscription draft, replacing the legacy start, subscriber, simulation, summary and confirm
 * actions of the Struts front controller. The draft is
 * persisted by souscription-service and replaces the 20-minute server-side conversational state of
 * the source; see {@code docs/stateless-model.md}.
 *
 * <p>The resource is stateless: the draft identifier is supplied by the client on every draft route
 * and typed as {@link UUID} so a malformed identifier is rejected structurally as 400. No
 * step-ordering guard is enforced here: sequencing belongs to souscription-service, which owns the
 * draft, and to the SPA router.
 */
@Path("/api/v1/drafts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "subscription", description = "Subscription draft and confirmation, proxied to souscription-service.")
public class DraftResource {

    private final SubscriptionGateway subscriptions;
    private final CatalogueGateway catalogue;

    @Inject
    public DraftResource(SubscriptionGateway subscriptions, CatalogueGateway catalogue) {
        this.subscriptions = subscriptions;
        this.catalogue = catalogue;
    }

    @POST
    @Operation(operationId = "startDraft",
            summary = "Start a subscription draft for a product (wizard step 1)")
    @SecurityRequirement(name = "csrfToken")
    @APIResponse(responseCode = "201", description = "Draft created.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = DraftView.class)))
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
    @APIResponse(responseCode = "503", description = "souscription-service indisponible (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "502", description = "Service aval en erreur (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "504", description = "Delai d'attente aval depasse (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    public Uni<Response> start(@Valid DraftStartRequest request) {
        // Les deux appels aval ne dependent pas l'un de l'autre : le code produit suffit a chacun,
        // ils sont donc composes en parallele et la latence de la route est max(2 appels) et non
        // leur somme (W5-T03, voir docs/latency-budget.md). Le produit rendu est celui du
        // catalogue, proprietaire de la donnee produit.
        return Uni.combine().all()
                .unis(subscriptions.startDraft(request), catalogue.detail(request.productCode()))
                .asTuple()
                .map(both -> withAuthoritativeProduct(both.getItem1(), both.getItem2()))
                .map(draft -> Response.created(URI.create("/api/v1/drafts/" + draft.draftId()))
                        .entity(draft)
                        .build());
    }

    private static DraftView withAuthoritativeProduct(DraftView draft, ProductDetail product) {
        return new DraftView(draft.draftId(), product, draft.subscriber(), draft.simulation(),
                draft.confirmationKey(), draft.status());
    }

    @GET
    @Path("/{draftId}")
    @Operation(operationId = "getDraft", summary = "Read the current draft (wizard summary, step 4)")
    @APIResponse(responseCode = "200", description = "The draft.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = DraftView.class)))
    @APIResponse(responseCode = "404", description = "Identifiant de brouillon inconnu (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "405", description = "Methode HTTP non autorisee (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "500", description = "Erreur interne (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "503", description = "souscription-service indisponible (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "502", description = "Service aval en erreur (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "504", description = "Delai d'attente aval depasse (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    public Uni<DraftView> get(@PathParam("draftId") UUID draftId) {
        return subscriptions.getDraft(draftId);
    }

    @PUT
    @Path("/{draftId}/subscriber")
    @Operation(operationId = "putDraftSubscriber",
            summary = "Capture or replace the subscriber of the draft (wizard step 2)")
    @SecurityRequirement(name = "csrfToken")
    @APIResponse(responseCode = "200", description = "Subscriber captured; the updated draft is returned.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = DraftView.class)))
    @APIResponse(responseCode = "400", description = "Validation structurelle en echec (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "403", description = "Jeton CSRF absent ou invalide (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "404", description = "Identifiant de brouillon inconnu (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "405", description = "Methode HTTP non autorisee (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "409", description = "Conflit avec l'etat courant du brouillon (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "500", description = "Erreur interne (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "503", description = "souscription-service indisponible (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "502", description = "Service aval en erreur (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "504", description = "Delai d'attente aval depasse (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    public Uni<DraftView> putSubscriber(@PathParam("draftId") UUID draftId,
                                        @Valid SubscriberInput subscriber) {
        return subscriptions.putSubscriber(draftId, subscriber);
    }

    @PUT
    @Path("/{draftId}/simulation")
    @Operation(operationId = "putDraftSimulation",
            summary = "Attach the chosen simulation to the draft (wizard step 3)")
    @SecurityRequirement(name = "csrfToken")
    @APIResponse(responseCode = "200", description = "Simulation attached; the updated draft is returned.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = DraftView.class)))
    @APIResponse(responseCode = "400", description = "Validation structurelle en echec (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "403", description = "Jeton CSRF absent ou invalide (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "404", description = "Identifiant de brouillon inconnu (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "405", description = "Methode HTTP non autorisee (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "409", description = "Conflit avec l'etat courant du brouillon (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "500", description = "Erreur interne (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "503", description = "souscription-service indisponible (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "502", description = "Service aval en erreur (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "504", description = "Delai d'attente aval depasse (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    public Uni<DraftView> putSimulation(@PathParam("draftId") UUID draftId,
                                        @Valid SimulationInput simulation) {
        return subscriptions.putSimulation(draftId, simulation);
    }

    /**
     * Confirms the draft. Idempotent on the draft's {@code
     * confirmationKey}: the first call answers 201 with a new reference, a replay answers 200 with
     * the same reference and creates nothing.
     *
     * <p>The key supplied by the client is propagated downstream both in the body and as the
     * {@code Idempotency-Key} header of the consumed contract (W4-T02); the gateway never generates
     * one, being stateless. No automatic retry is attempted on this call: on a timeout the caller
     * receives 504 and re-submits the same key (see {@code docs/error-mapping.md}).
     */
    @POST
    @Path("/{draftId}/confirmation")
    @Operation(operationId = "confirmDraft",
            summary = "Confirm the draft and create the subscription (wizard step 5)")
    @SecurityRequirement(name = "csrfToken")
    @APIResponse(responseCode = "201", description = "Subscription created.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = SubscriptionConfirmation.class)))
    @APIResponse(responseCode = "200",
            description = "Replay of an already-confirmed key; the same subscription is returned.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = SubscriptionConfirmation.class)))
    @APIResponse(responseCode = "400", description = "Validation structurelle en echec (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "403", description = "Jeton CSRF absent ou invalide (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "404", description = "Identifiant de brouillon inconnu (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "405", description = "Methode HTTP non autorisee (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "409",
            description = "Brouillon incomplet ou confirmationKey obsolete (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "500", description = "Erreur interne (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "503", description = "souscription-service indisponible (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "502", description = "Service aval en erreur (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "504", description = "Delai d'attente aval depasse (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @WithSpan("confirm-journey")
    public Uni<Response> confirm(@PathParam("draftId") UUID draftId,
                                 @Valid ConfirmationRequest request) {
        Journeys.tag(Journeys.CONFIRM);
        return subscriptions.confirm(draftId, request.confirmationKey(), request)
                .map(response -> new ConfirmationOutcome(
                        response.getStatus() == Response.Status.CREATED.getStatusCode(),
                        response.getEntity()))
                .map(outcome -> outcome.created()
                        ? Response.created(URI.create(
                                "/api/v1/subscriptions/" + outcome.subscription().reference()))
                                .entity(outcome.subscription())
                                .build()
                        : Response.ok(outcome.subscription()).build());
    }
}
