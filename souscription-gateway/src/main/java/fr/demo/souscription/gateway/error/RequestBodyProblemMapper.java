package fr.demo.souscription.gateway.error;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps a request body Jackson cannot bind (unknown property under
 * {@code fail-on-unknown-properties}, wrong type, missing creator property) to 400 problem+json. Without it the built-in
 * Quarkus REST mapper answers plain {@code application/json} naming the DTO class and attribute,
 * outside the problem+json contract (NFG-018) and without the journal entry of plan 27.
 */
@Provider
public class RequestBodyProblemMapper implements ExceptionMapper<MismatchedInputException> {

    @Inject
    ProblemResponses problems;

    @Override
    public Response toResponse(MismatchedInputException e) {
        return problems.toResponse(new BadRequestException(e));
    }
}
