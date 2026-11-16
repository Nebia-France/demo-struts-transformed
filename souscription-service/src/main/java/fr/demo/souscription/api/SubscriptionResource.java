package fr.demo.souscription.api;

import fr.demo.souscription.api.dto.ConfirmationRequest;
import fr.demo.souscription.api.dto.DraftView;
import fr.demo.souscription.api.dto.FieldErrors;
import fr.demo.souscription.api.dto.ProductDetail;
import fr.demo.souscription.api.dto.SimulationInput;
import fr.demo.souscription.api.dto.SimulationResult;
import fr.demo.souscription.api.dto.StartDraftRequest;
import fr.demo.souscription.api.dto.SubscriberInput;
import fr.demo.souscription.api.dto.SubscriptionConfirmation;
import fr.demo.souscription.api.mapper.ModelMapper;
import fr.demo.souscription.application.ConfirmationService;
import fr.demo.souscription.application.DraftService;
import fr.demo.souscription.application.DraftStateException;
import fr.demo.souscription.integration.CatalogueLookup;
import fr.demo.souscription.integration.SimulationLookup;
import fr.demo.souscription.integration.SimulationRejectedException;
import fr.demo.souscription.integration.SimulationComputeRequest;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Ressource REST du brouillon de souscription (W3-T05).
 *
 * <p>Remplace le dispatch par chaine du chemin Struts de {@code action/SubscriptionAction}, qui
 * retournait des redirections : une operation du contrat gele =
 * une methode. Les redirections inter-etapes disparaissent, chaque operation renvoie l'etat du
 * brouillon sous forme de {@link DraftView} ; la navigation appartient au routeur Angular de
 * souscription-web. Les erreurs de methode et de chemin ne sont pas reimplementees : JAX-RS
 * produit nativement 405 et 404.
 *
 * <p>Aucune logique d'ordonnancement du parcours ici : les transitions restent dans
 * {@link DraftService}. Les corps structures {@code application/problem+json} derriere
 * 400 / 409 / 503 sont installes par le plan 22.
 */
@Path("/v1/drafts")
@Tag(name = "drafts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SubscriptionResource {

    @Inject
    DraftService draftService;

    @Inject
    CatalogueLookup catalogue;

    @Inject
    SimulationLookup simulations;

    @Inject
    ConfirmationService confirmationService;

    /**
     * Un code produit inconnu du catalogue repond 404 et non 400 : ecart assume par
     * {@code STRUTS_REST_COVERAGE.md} §1, le contrat gele reservant 400 au corps invalide.
     */
    @POST
    @Operation(operationId = "startDraft", summary = "Create a subscription draft for a product")
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Draft created.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = DraftView.class))),
            @APIResponse(responseCode = "400", description = "Field validation failed.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "404", description = "Unknown product code.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "500", description = "Unexpected server error.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "503", description = "The catalogue is unavailable.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class)))
    })
    @Blocking
    public Response startDraft(@Valid StartDraftRequest request) {
        ProductDetail product = catalogue.findProduct(request.productCode())
                .orElseThrow(() -> new NotFoundException(
                        "produit inconnu au catalogue : " + request.productCode()));
        DraftView draft = draftService.start(request.productCode(), ModelMapper.toDomain(product));
        // Location relative posee comme en-tete brut : Response.created(URI) la resoudrait contre
        // l'URI de base et exposerait l'hote interne derriere la gateway.
        return Response.status(Response.Status.CREATED)
                .header(HttpHeaders.LOCATION, "/v1/drafts/" + draft.draftId())
                .entity(withDescription(draft, product.description()))
                .build();
    }

    /**
     * Servi uniquement depuis le brouillon persiste, sans appel sortant. Un brouillon expire est
     * traite comme absent : 404, le contrat ne declarant aucune reponse Gone.
     */
    @GET
    @Path("/{draftId}")
    @Operation(operationId = "getDraft", summary = "Read a draft")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "The draft.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = DraftView.class))),
            @APIResponse(responseCode = "404", description = "Unknown or expired draft id.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "500", description = "Unexpected server error.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class)))
    })
    @Blocking
    public DraftView getDraft(@PathParam("draftId") UUID draftId) {
        return draftService.read(draftId).orElseThrow(NotFoundException::new);
    }

    @PUT
    @Path("/{draftId}/subscriber")
    @Operation(operationId = "putDraftSubscriber",
            summary = "Capture or replace the subscriber of a draft")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "The updated draft.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = DraftView.class))),
            @APIResponse(responseCode = "400", description = "Field validation failed.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "404", description = "Unknown or expired draft id.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "409", description = "The request conflicts with the current state of the draft.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "500", description = "Unexpected server error.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class)))
    })
    @Blocking
    public DraftView putDraftSubscriber(@PathParam("draftId") UUID draftId,
                                        @Valid SubscriberInput request) {
        try {
            return draftService.updateSubscriber(draftId, ModelMapper.toDomain(request));
        } catch (NoSuchElementException unknown) {
            throw new NotFoundException(unknown.getMessage(), unknown);
        }
    }

    /**
     * Seul le resultat calcule par le serveur est enregistre : une projection fournie par le
     * client n'est jamais reprise. Le taux annuel vient de l'instantane produit porte par le
     * brouillon, jamais du corps de la requete.
     */
    @PUT
    @Path("/{draftId}/simulation")
    @Operation(operationId = "putDraftSimulation", summary = "Attach a simulation to a draft")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "The updated draft, carrying the server-computed simulation.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = DraftView.class))),
            @APIResponse(responseCode = "400", description = "Field validation failed.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "404", description = "Unknown or expired draft id.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "409", description = "The request conflicts with the current state of the draft.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "500", description = "Unexpected server error.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "503", description = "The catalogue or the simulation service is unavailable.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class)))
    })
    @Blocking
    public DraftView putDraftSimulation(@PathParam("draftId") UUID draftId,
                                        @Valid SimulationInput request) {
        DraftView draft = draftService.read(draftId).orElseThrow(NotFoundException::new);
        SimulationResult computed;
        try {
            computed = simulations.compute(new SimulationComputeRequest(request.initialAmount(),
                    request.monthlyAmount(), request.durationYears(),
                    draft.product().annualRate()));
        } catch (SimulationRejectedException refused) {
            // Verdict du fournisseur : relaye tel quel en 400 par SimulationRejectedMapper, avec
            // ses erreurs par champ. L'envelopper ici les perdrait.
            throw refused;
        } catch (IllegalArgumentException invalid) {
            throw new ClientErrorException(invalid.getMessage(), Response.Status.BAD_REQUEST,
                    invalid);
        }
        try {
            return draftService.updateSimulation(draftId, ModelMapper.toDomain(computed));
        } catch (NoSuchElementException unknown) {
            throw new NotFoundException(unknown.getMessage(), unknown);
        } catch (IllegalStateException outOfOrder) {
            throw new DraftStateException("confirmationKey",
                    ConfirmationService.PRECONDITION_SEQUENCE, outOfOrder.getMessage());
        }
    }

    /**
     * Confirme le brouillon et persiste la souscription.
     *
     * <p>Rejouer la meme {@code confirmationKey} renvoie la meme {@code reference} avec le statut
     * <b>200</b> et ne cree jamais une seconde ligne ; la premiere confirmation renvoie
     * <b>201</b>. Les deux statuts sont declares par le contrat gele et tous deux atteignables.
     *
     * <p>Remplace le conflit brut et les redirections de reprise du
     * {@code /confirmer} source : cle divergente et parcours incomplet repondent 409, un
     * brouillon absent ou expire repond 404, une indisponibilite de stockage repond 503.
     */
    @POST
    @Path("/{draftId}/confirmation")
    @Operation(operationId = "confirmDraft",
            summary = "Confirm a draft and persist the subscription (idempotent on confirmationKey)")
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Subscription created.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = SubscriptionConfirmation.class))),
            @APIResponse(responseCode = "200", description = "Replay of an already-confirmed key; the same subscription is returned.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = SubscriptionConfirmation.class))),
            @APIResponse(responseCode = "400", description = "Field validation failed.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "404", description = "Unknown or expired draft id.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "409", description = "Incomplete draft or a confirmationKey that no longer matches the draft.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "500", description = "Unexpected server error.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class))),
            @APIResponse(responseCode = "503", description = "The catalogue or the simulation service is unavailable.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = FieldErrors.class)))
    })
    @Blocking
    public Response confirmDraft(@PathParam("draftId") UUID draftId,
                                 @Valid ConfirmationRequest request) {
        ConfirmationService.Outcome outcome =
                confirmationService.confirmIdempotent(draftId, request.confirmationKey());
        if (!outcome.created()) {
            return Response.ok(outcome.confirmation()).build();
        }
        return Response.status(Response.Status.CREATED)
                .header(HttpHeaders.LOCATION,
                        "/v1/subscriptions/" + outcome.confirmation().reference())
                .entity(outcome.confirmation())
                .build();
    }

    /**
     * La description du produit est detenue par le catalogue et n'est pas persistee sur le
     * brouillon : elle est rattachee ici, quand l'appelant la detient deja.
     */
    private static DraftView withDescription(DraftView draft, String description) {
        ProductDetail product = draft.product();
        return new DraftView(draft.draftId(),
                new ProductDetail(product.code(), product.name(), product.profile(), description,
                        product.annualRate()),
                draft.subscriber(), draft.simulation(), draft.confirmationKey(), draft.status());
    }
}
