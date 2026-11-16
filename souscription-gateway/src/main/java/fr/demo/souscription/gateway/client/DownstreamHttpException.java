package fr.demo.souscription.gateway.client;

import fr.demo.souscription.gateway.api.dto.FieldErrors;
import java.util.List;

/**
 * Raised by a downstream client when the called service answered with an HTTP error status.
 *
 * <p>Carries the downstream status so the frozen mapping of {@code docs/error-mapping.md} can be
 * applied: a relayable 4xx keeps its meaning, any 5xx becomes a 502 at the edge.
 *
 * <p>It also carries the field-level errors the validating service produced, when it produced any:
 * they are the only part of a downstream body allowed through the public surface (W4-T05), and they
 * are relayed verbatim in the {@code errors} member of the problem document.
 */
public class DownstreamHttpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int downstreamStatus;

    private final List<FieldErrors.FieldError> fieldErrors;

    public DownstreamHttpException(int downstreamStatus, String message) {
        this(downstreamStatus, message, null);
    }

    public DownstreamHttpException(int downstreamStatus, String message,
            List<FieldErrors.FieldError> fieldErrors) {
        super(message);
        this.downstreamStatus = downstreamStatus;
        // Declaration order of the downstream payload is preserved: the relay is verbatim.
        this.fieldErrors = fieldErrors == null || fieldErrors.isEmpty() ? null
                : List.copyOf(fieldErrors);
    }

    public int downstreamStatus() {
        return downstreamStatus;
    }

    /**
     * The rejected fields, their messages and rule codes as the downstream service wrote them, or
     * {@code null} when the failure carried none.
     */
    public List<FieldErrors.FieldError> fieldErrors() {
        return fieldErrors;
    }
}
