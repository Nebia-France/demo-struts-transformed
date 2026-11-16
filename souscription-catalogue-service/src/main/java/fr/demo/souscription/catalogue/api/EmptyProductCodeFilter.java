package fr.demo.souscription.catalogue.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Quarkus REST normalises a trailing slash away before matching, so {@code GET /v1/products/}
 * would otherwise be served by the collection endpoint. The source {@code CatalogAction} answered
 * 404 for an empty product code, so the empty code is rejected here before matching and rendered
 * by {@link ProductNotFoundMapper}.
 */
@Provider
@PreMatching
public class EmptyProductCodeFilter implements ContainerRequestFilter {

    private static final String EMPTY_CODE_PATH = "/v1/products/";

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (EMPTY_CODE_PATH.equals(path) && "GET".equals(requestContext.getMethod())) {
            throw new ProductNotFoundException("");
        }
    }
}
