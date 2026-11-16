package fr.demo.souscription.gateway.error;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps a rejected HTTP method to 405 problem+json. Declared explicitly because the method
 * allow-list filter of plan 13 depends on this mapper for the response it short-circuits with.
 *
 * <p>Every 405 carries {@code Allow}, as RFC 9110 requires (W7-T02): the header of the exception
 * when present, otherwise the methods the resources declare for the request path
 * ({@link DeclaredMethods}), since Quarkus REST raises the exception without it.
 */
@Provider
public class NotAllowedProblemMapper implements ExceptionMapper<NotAllowedException> {

    @Inject
    ProblemResponses problems;

    @Inject
    DeclaredMethods declaredMethods;

    @Inject
    CurrentVertxRequest currentRequest;

    @Override
    public Response toResponse(NotAllowedException e) {
        Response problem = problems.toResponse(e);
        String allow = e.getResponse() == null ? null : e.getResponse().getHeaderString("Allow");
        if (allow == null || allow.isBlank()) {
            RoutingContext context = currentRequest == null ? null : currentRequest.getCurrent();
            allow = context == null ? null : declaredMethods.allowFor(context.normalizedPath());
        }
        if (allow == null) {
            return problem;
        }
        return Response.fromResponse(problem).header("Allow", allow).build();
    }
}
