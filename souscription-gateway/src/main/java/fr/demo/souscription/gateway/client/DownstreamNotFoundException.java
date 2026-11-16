package fr.demo.souscription.gateway.client;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Raised by a downstream client when the requested product code, draft id or subscription
 * reference is unknown, so the gateway answers 404.
 *
 * <p>The problem+json payload is normalised in plan 12 and the full downstream status mapping is
 * installed in plan 20; here only the status code matters.
 */
public class DownstreamNotFoundException extends WebApplicationException {

    private static final long serialVersionUID = 1L;

    public DownstreamNotFoundException(String message) {
        super(message, Response.Status.NOT_FOUND);
    }
}
