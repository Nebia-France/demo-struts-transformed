package fr.demo.souscription.gateway.api;

import fr.demo.souscription.gateway.api.dto.SubscriptionConfirmation;
import fr.demo.souscription.gateway.client.SubscriptionGateway;
import fr.demo.souscription.gateway.error.Problem;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Confirmed-subscription read, replacing the legacy confirmation display action.
 *
 * <p>The confirmation write itself ({@code POST /api/v1/drafts/{draftId}/confirmation}) lives on
 * {@link DraftResource} because Jakarta REST selects the
 * best-matching root resource class from the request URI before matching methods: a second root
 * class rooted at {@code /api/v1} would be shadowed by {@code /api/v1/drafts}.
 */
@Path("/api/v1/subscriptions")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "subscription", description = "Subscription draft and confirmation, proxied to souscription-service.")
public class ConfirmationResource {

    private final SubscriptionGateway subscriptions;

    @Inject
    public ConfirmationResource(SubscriptionGateway subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GET
    @Path("/{reference}")
    @Operation(operationId = "getSubscription", summary = "Read a confirmed subscription")
    @APIResponse(responseCode = "200", description = "The subscription.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = SubscriptionConfirmation.class)))
    @APIResponse(responseCode = "404", description = "Reference de souscription inconnue (RFC 7807)",
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
    public Uni<SubscriptionConfirmation> get(@PathParam("reference") String reference) {
        return subscriptions.getSubscription(reference);
    }
}
