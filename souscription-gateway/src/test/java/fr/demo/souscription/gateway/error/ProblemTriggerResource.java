package fr.demo.souscription.gateway.error;

import fr.demo.souscription.gateway.client.DownstreamHttpException;
import fr.demo.souscription.gateway.client.IdempotencyConflictException;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;

/**
 * Test-only routes raising one failure per entry of the frozen status table, so
 * {@link ProblemMapperTest} can assert the public contract without depending on the downstream
 * clients of plan 17 or on the resilience policies of plan 20.
 */
@Path("/test-only/problems")
public class ProblemTriggerResource {

    @GET
    @Path("/bad-request")
    public String badRequest() {
        throw new BadRequestException("forced 400");
    }

    @GET
    @Path("/validation")
    public String validation(@QueryParam("code") @NotBlank String code) {
        return code;
    }

    @GET
    @Path("/forbidden")
    public String forbidden() {
        throw new ForbiddenException("forced 403");
    }

    @GET
    @Path("/not-found")
    public String notFound() {
        throw new NotFoundException("forced 404");
    }

    @GET
    @Path("/method")
    public String methodOnlyGet() {
        return "ok";
    }

    @GET
    @Path("/conflict")
    public String conflict() {
        throw new IdempotencyConflictException("forced 409");
    }

    @GET
    @Path("/boom")
    public String boom() {
        throw new IllegalStateException("forced 500 at fr.demo internal detail");
    }

    @GET
    @Path("/downstream-4xx")
    public String downstreamRelayable() {
        throw new DownstreamHttpException(409, "downstream conflict");
    }

    @GET
    @Path("/downstream-5xx")
    public String downstreamServerError() {
        throw new DownstreamHttpException(500, "downstream unavailable");
    }

    /**
     * A downstream 503 is the one relayed 5xx: it stays a 503 with {@code Retry-After} instead of
     * becoming a 502 (W4-T05).
     */
    @GET
    @Path("/downstream-503")
    public String downstreamUnavailable() {
        throw new DownstreamHttpException(503, "downstream unavailable");
    }

    @GET
    @Path("/timeout")
    public String timeout() {
        throw new TimeoutException("forced timeout");
    }

    @GET
    @Path("/circuit-open")
    public String circuitOpen() {
        throw new CircuitBreakerOpenException("forced open circuit");
    }
}
