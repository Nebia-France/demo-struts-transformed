package fr.demo.souscription.gateway.client;

import fr.demo.souscription.gateway.api.dto.ProductDetail;
import fr.demo.souscription.gateway.api.dto.ProductSummary;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Downstream view of souscription-catalogue-service, the authoritative source of the product
 * annual rate.
 *
 * <p>Paths are read from
 * {@code global/contracts/souscription-gateway-to-souscription-catalogue-service/openapi.yaml}
 * ({@code GET /v1/products}, {@code GET /v1/products/{code}}); the base URI and the timeouts come
 * from {@code quarkus.rest-client.catalogue.*}.
 */
@Path("/v1/products")
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "catalogue")
@RegisterProvider(DownstreamResponseExceptionMapper.class)
public interface CatalogueClient {

    @GET
    Uni<List<ProductSummary>> list();

    /**
     * @return the product, or a failure carrying {@link DownstreamNotFoundException} when the code
     *     is unknown.
     */
    @GET
    @Path("/{code}")
    Uni<ProductDetail> detail(@PathParam("code") String code);
}
