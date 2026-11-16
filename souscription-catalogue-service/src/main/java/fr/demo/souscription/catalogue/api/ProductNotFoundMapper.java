package fr.demo.souscription.catalogue.api;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

@Provider
public class ProductNotFoundMapper implements ExceptionMapper<ProductNotFoundException> {

    private static final Logger LOG = Logger.getLogger(ProductNotFoundMapper.class);

    @Override
    public Response toResponse(ProductNotFoundException exception) {
        LOG.warnf("Code produit inconnu demande : %s", exception.code());
        return Response.status(Response.Status.NOT_FOUND)
                .type(ProblemMediaType.APPLICATION_PROBLEM_JSON)
                .entity(new ProblemDetail(
                        "https://horizon.demo/problems/produit-inconnu",
                        "Produit inconnu", 404,
                        "Aucun produit du catalogue ne correspond au code demande.",
                        "/v1/products"))
                .build();
    }
}
