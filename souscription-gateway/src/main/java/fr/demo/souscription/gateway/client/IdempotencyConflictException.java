package fr.demo.souscription.gateway.client;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Raised when a confirmation is replayed with the same idempotency key but a different payload, or
 * when the draft it refers to has already been confirmed: the gateway answers 409.
 *
 * <p>Replaces the 409 error page of the source deployment descriptor, which told the user the
 * summary was stale.
 */
public class IdempotencyConflictException extends WebApplicationException {

    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException(String message) {
        super(message, Response.Status.CONFLICT);
    }
}
