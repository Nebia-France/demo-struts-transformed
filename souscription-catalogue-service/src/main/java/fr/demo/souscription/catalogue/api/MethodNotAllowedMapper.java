package fr.demo.souscription.catalogue.api;

import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Quarkus REST produit nativement un 405 sans corps. Ce mapper lui donne un corps
 * {@code application/problem+json} statique, tout en conservant l'en-tete {@code Allow}.
 */
@Provider
public class MethodNotAllowedMapper implements ExceptionMapper<NotAllowedException> {

    @Override
    public Response toResponse(NotAllowedException exception) {
        Response.ResponseBuilder builder = Response.status(Response.Status.METHOD_NOT_ALLOWED)
                .type(ProblemMediaType.APPLICATION_PROBLEM_JSON)
                .entity(new ProblemDetail(
                        "https://horizon.demo/problems/methode-non-autorisee",
                        "Methode non autorisee", 405,
                        "La methode HTTP utilisee n'est pas autorisee sur cette ressource.",
                        "/v1/products"));
        Object allow = exception.getResponse() == null
                ? null
                : exception.getResponse().getHeaderString("Allow");
        if (allow != null) {
            builder.header("Allow", allow);
        }
        return builder.build();
    }
}
