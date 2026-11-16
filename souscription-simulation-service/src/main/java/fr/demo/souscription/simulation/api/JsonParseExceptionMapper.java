package fr.demo.souscription.simulation.api;

import com.fasterxml.jackson.core.JacksonException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Un corps JSON illisible (ou un champ au mauvais type) est une erreur client : 400 en
 * application/problem+json, jamais une page HTML de conteneur ni un 500. Le message Jackson
 * (position, nom de classe) n'est pas exposé.
 */
@Provider
public class JsonParseExceptionMapper implements ExceptionMapper<JacksonException> {

    @Context
    HttpHeaders headers;

    @Override
    public Response toResponse(JacksonException exception) {
        return ProblemJson.response(400, "Malformed request body",
                "Le corps de la requête n'est pas un document JSON valide.", null, headers);
    }
}
