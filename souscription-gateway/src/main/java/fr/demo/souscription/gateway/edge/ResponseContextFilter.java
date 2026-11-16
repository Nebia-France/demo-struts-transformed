package fr.demo.souscription.gateway.edge;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;

/**
 * JAX-RS response interception skeleton: proves the {@code @Provider} wiring of the edge package.
 *
 * <p>Deliberately empty. The security response headers are <em>not</em> set here: a
 * {@code ContainerResponseFilter} never sees responses that do not reach JAX-RS (management
 * endpoints under {@code /q/*}, non-JAX-RS 404s), so they are applied by a Vert.x
 * {@code @RouteFilter} using {@code addHeadersEndHandler} in plan 11. Any concern that genuinely
 * needs the JAX-RS request context belongs here instead.
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
public class ResponseContextFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response)
            throws IOException {
        // Intentionally empty: see plan 11 (souscription-gateway/docs/edge-filters.md).
    }
}
