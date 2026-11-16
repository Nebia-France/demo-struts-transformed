package fr.demo.souscription.catalogue.api;

import fr.demo.souscription.catalogue.service.CatalogService;
import io.micrometer.core.annotation.Timed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

import java.util.List;

@Path("/v1/products")
@Produces(MediaType.APPLICATION_JSON)
public class ProductResource {

    @Inject
    CatalogService catalogService;

    @ConfigProperty(name = "catalogue.cache.max-age-seconds")
    int maxAgeSeconds;

    @GET
    @Timed(value = "catalogue.products.list", description = "Latence de GET /v1/products",
            percentiles = {0.95, 0.99})
    @Operation(summary = "Liste les produits du catalogue Horizon")
    @APIResponse(responseCode = "200", description = "Catalogue complet",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(type = SchemaType.ARRAY, implementation = ProductSummary.class)))
    @APIResponse(responseCode = "405", description = "Methode non autorisee",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @APIResponse(responseCode = "500", description = "Erreur interne",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public Response list(@Context Request request) {
        EntityTag etag = new EntityTag(catalogService.version());
        Response.ResponseBuilder notModified = request.evaluatePreconditions(etag);
        if (notModified != null) {
            return notModified.cacheControl(cacheControl()).build();
        }
        List<ProductSummary> products =
                catalogService.getProducts().stream().map(ProductSummary::from).toList();
        return Response.ok(products).tag(etag).cacheControl(cacheControl()).build();
    }

    @GET
    // The uri tag of the HTTP server metric is built from this template, so it renders as
    // /v1/products/{code: .+} -- templatised, never the code value (W5-T02, high cardinality).
    // The regex is part of the frozen /v1 contract (pattern: .+) and is kept as is.
    @Path("/{code: .+}")
    @Timed(value = "catalogue.products.detail", description = "Latence de GET /v1/products/{code}",
            percentiles = {0.95, 0.99})
    @Operation(summary = "Fiche d'un produit du catalogue Horizon")
    @APIResponse(responseCode = "200", description = "Fiche produit",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ProductDetail.class)))
    @APIResponse(responseCode = "404", description = "Code produit inconnu",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @APIResponse(responseCode = "500", description = "Erreur interne",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public Response byCode(@PathParam("code") String code, @Context Request request) {
        ProductDetail detail = catalogService.find(code)
                .map(ProductDetail::from)
                .orElseThrow(() -> new ProductNotFoundException(code));
        EntityTag etag = new EntityTag(catalogService.version() + "-" + code);
        Response.ResponseBuilder notModified = request.evaluatePreconditions(etag);
        if (notModified != null) {
            return notModified.cacheControl(cacheControl()).build();
        }
        return Response.ok(detail).tag(etag).cacheControl(cacheControl()).build();
    }

    private CacheControl cacheControl() {
        return CacheControl.valueOf("max-age=" + maxAgeSeconds + ", public");
    }
}
