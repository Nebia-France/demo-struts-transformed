package fr.demo.souscription.api;

import fr.demo.souscription.api.dto.FieldErrors;
import fr.demo.souscription.api.dto.SubscriptionConfirmation;
import fr.demo.souscription.application.ConfirmationService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Lecture de la souscription confirmee (W3-T06).
 *
 * <p>Ressource distincte de {@link SubscriptionResource} : la racine de chemin differe
 * ({@code /v1/subscriptions} et non {@code /v1/drafts}).
 *
 * <p>Remplace le {@code /confirmation} Struts, qui lisait l'attribut de session {@code completed}
 * et redirigeait vers le catalogue quand il etait nul : une reference inconnue est desormais un
 * <b>404 Not Found</b> explicite, le contrat gele ne declarant aucune reponse Gone. Le POST sur cette
 * ressource repond le 405 natif de JAX-RS, aucune garde n'etant ecrite a la main.
 *
 * <p>Servi uniquement depuis la ligne denormalisee de {@code subscriptions.subscriptions} :
 * aucun appel au catalogue ni a la simulation.
 */
@Path("/v1/subscriptions")
@Tag(name = "subscriptions")
@Produces(MediaType.APPLICATION_JSON)
public class SubscriptionReadResource {

    @Inject
    ConfirmationService confirmationService;

    @GET
    @Path("/{reference}")
    @Operation(operationId = "getSubscription", summary = "Read a confirmed subscription")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "The subscription.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = SubscriptionConfirmation.class))),
            @APIResponse(responseCode = "404", description = "Unknown subscription reference.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "500", description = "Unexpected server error.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class)))
    })
    @Blocking
    public SubscriptionConfirmation getSubscription(@PathParam("reference") String reference) {
        return confirmationService.findByReference(reference)
                .orElseThrow(NotFoundException::new);
    }
}
