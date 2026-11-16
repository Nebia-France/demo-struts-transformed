package fr.demo.souscription.gateway.client;

import fr.demo.souscription.gateway.api.dto.ConfirmationRequest;
import fr.demo.souscription.gateway.api.dto.DraftStartRequest;
import fr.demo.souscription.gateway.api.dto.DraftView;
import fr.demo.souscription.gateway.api.dto.SimulationInput;
import fr.demo.souscription.gateway.api.dto.SubscriberInput;
import fr.demo.souscription.gateway.api.dto.SubscriptionConfirmation;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Downstream view of souscription-service, owner of the persisted draft and of the subscriptions
 * table.
 *
 * <p>Paths are read from
 * {@code global/contracts/souscription-gateway-to-souscription-service/openapi.yaml}; the base URI
 * and the timeouts come from {@code quarkus.rest-client.subscription.*}. Step sequencing and
 * validation stay downstream: the gateway does not reproduce the command dispatch of the legacy
 * {@code SubscriptionAction}.
 */
@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "subscription")
@RegisterClientHeaders(SubscriptionCsrfHeaders.class)
@RegisterProvider(DownstreamResponseExceptionMapper.class)
public interface SubscriptionClient {

    /**
     * Header carrying the confirmation key on the wire, as declared by the consumed contract.
     */
    String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    @POST
    @Path("/drafts")
    Uni<DraftView> startDraft(DraftStartRequest request);

    @GET
    @Path("/drafts/{draftId}")
    Uni<DraftView> getDraft(@PathParam("draftId") UUID draftId);

    @PUT
    @Path("/drafts/{draftId}/subscriber")
    Uni<DraftView> putSubscriber(@PathParam("draftId") UUID draftId, SubscriberInput subscriber);

    @PUT
    @Path("/drafts/{draftId}/simulation")
    Uni<DraftView> putSimulation(@PathParam("draftId") UUID draftId, SimulationInput simulation);

    /**
     * Idempotent on {@code confirmationKey}: the provider answers 201 on the first call and 200 on
     * any replay. The status is the only way to tell them apart, hence the typed
     * {@link RestResponse} envelope, which {@code DraftResource} narrows to a
     * {@link ConfirmationOutcome}.
     *
     * <p>The key also travels as the {@code Idempotency-Key} header declared by
     * {@code global/contracts/souscription-gateway-to-souscription-service/openapi.yaml}: the source
     * enforced idempotency in-process through the unique {@code confirmation_key} constraint, and
     * that guarantee only survives the call becoming remote if the key is on the wire (W4-T02).
     *
     * <p><strong>No automatic retry is declared on this call</strong> — deliberately, and this is
     * the only write edge of the gateway. From the transport's point of view the outcome of a
     * timed-out POST is unknown, so a transparent retry could duplicate a user action even though
     * the provider is idempotent on the key. A timeout is surfaced as 504 and the caller re-submits
     * the <em>same</em> key; see {@code docs/error-mapping.md}.
     */
    @POST
    @Path("/drafts/{draftId}/confirmation")
    Uni<RestResponse<SubscriptionConfirmation>> confirm(
            @PathParam("draftId") UUID draftId,
            @HeaderParam(IDEMPOTENCY_KEY_HEADER) String confirmationKey,
            ConfirmationRequest request);

    @GET
    @Path("/subscriptions/{reference}")
    Uni<SubscriptionConfirmation> getSubscription(@PathParam("reference") String reference);
}
