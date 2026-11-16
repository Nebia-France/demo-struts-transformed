package fr.demo.souscription.gateway.error;

import fr.demo.souscription.gateway.api.dto.FieldErrors;
import java.util.List;

/**
 * RFC 7807 Problem Details, the single error representation of the gateway (W2-T03).
 *
 * <p>Replaces the seven container error pages declared by the source deployment descriptor, which
 * all forwarded to one server-rendered HTML view. User-facing labels are not ported: they belong to
 * souscription-web, which renders them from {@link #status()} and {@link #errors()}.
 *
 * <p>{@link #detail()} never carries an internal diagnostic: correlation happens through
 * {@link #instance()}, set to the request id.
 *
 * <p>{@link #errors()} has the shape declared by
 * {@code global/contracts/shared-schemas/FieldErrors.json}, the reference of the public contract:
 * one {@code {field, message, code}} entry per rejected field (W7-T03).
 */
public record Problem(String type, String title, int status, String detail, String instance,
                      List<FieldErrors.FieldError> errors) {
}
