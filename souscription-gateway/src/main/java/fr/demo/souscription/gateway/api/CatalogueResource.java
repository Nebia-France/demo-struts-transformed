package fr.demo.souscription.gateway.api;

import fr.demo.souscription.gateway.api.dto.ProductDetail;
import fr.demo.souscription.gateway.api.dto.ProductSummary;
import fr.demo.souscription.gateway.client.CatalogueGateway;
import fr.demo.souscription.gateway.error.Problem;
import fr.demo.souscription.gateway.observability.Journeys;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Catalogue reads, replacing the two legacy catalogue actions (list and product sheet) of {@code
 * CatalogAction}. No view is rendered and no forward is performed: navigation belongs to
 * souscription-web.
 */
@Path("/api/v1/products")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "catalogue", description = "Product catalogue reads, proxied to souscription-catalogue-service.")
public class CatalogueResource {

    private final CatalogueGateway catalogue;

    @jakarta.ws.rs.core.Context
    jakarta.ws.rs.core.UriInfo uriInfo;

    @Inject
    public CatalogueResource(CatalogueGateway catalogue) {
        this.catalogue = catalogue;
    }

    @GET
    @Operation(operationId = "listProducts", summary = "List the savings products")
    @APIResponse(responseCode = "200", description = "The catalogue.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ProductSummary[].class)))
    @APIResponse(responseCode = "405", description = "Methode HTTP non autorisee (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "500", description = "Erreur interne (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "503", description = "Catalogue indisponible (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "502", description = "Service aval en erreur (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "504", description = "Delai d'attente aval depasse (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @WithSpan("catalogue-journey")
    public Uni<List<ProductSummary>> list() {
        Journeys.tag(Journeys.CATALOGUE);
        // "/api/v1/products/" addresses a product sheet whose code is empty, not the collection:
        // it is answered 404, like any unknown code.
        if (uriInfo.getPath().endsWith("/")) {
            return Uni.createFrom().failure(new jakarta.ws.rs.NotFoundException());
        }
        // Immutable view: the relayed collection must not become mutable at the edge.
        return catalogue.list().map(List::copyOf);
    }

    @GET
    @Path("/{code}")
    @Operation(operationId = "getProduct", summary = "Get one product by code")
    @APIResponse(responseCode = "200", description = "The product.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ProductDetail.class)))
    @APIResponse(responseCode = "404", description = "Code produit inconnu (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "405", description = "Methode HTTP non autorisee (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "500", description = "Erreur interne (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "503", description = "Catalogue indisponible (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "502", description = "Service aval en erreur (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "504", description = "Delai d'attente aval depasse (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @WithSpan("catalogue-journey")
    public Uni<ProductDetail> detail(@PathParam("code") String code) {
        Journeys.tag(Journeys.CATALOGUE);
        return catalogue.detail(code);
    }
}
