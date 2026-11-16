package fr.demo.souscription.gateway.edge;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;

/**
 * Test-only routes used by {@link SecurityHeaderFilterTest} to produce a JAX-RS 404 and a 500
 * without depending on the downstream exception mapping of plan 20.
 */
@Path("/test-only/failures")
public class ForcedFailureResource {

    @GET
    @Path("/not-found")
    public String notFound() {
        throw new NotFoundException("forced 404");
    }

    @GET
    @Path("/boom")
    public String boom() {
        throw new IllegalStateException("forced 500");
    }
}
