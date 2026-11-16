package fr.demo.souscription.gateway.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.demo.souscription.gateway.api.dto.FieldErrors;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/**
 * The single translation path from a downstream HTTP error to the gateway's own failure
 * vocabulary (W4-T05), registered on the three REST client interfaces with
 * {@code @RegisterProvider}. Whatever edge failed, the public answer is the one frozen in
 * {@code docs/error-mapping.md}: a relayable 4xx (400, 404, 409, 422) keeps its status, any other
 * downstream status becomes a 502, a timeout a 504 and an open circuit a 503.
 *
 * <p>404 and 409 keep their meaning because the three consumed contracts give them the same meaning
 * as the public API; every other status is relayed as a {@link DownstreamHttpException}, which
 * {@code ProblemFactory} narrows to a relayed 4xx or a 502.
 *
 * <p>The downstream body is never propagated: only the field-level errors produced by a validating
 * service are extracted here and relayed verbatim in the {@code errors} member of the problem
 * document. Everything else — message, stack trace, service name, received status — stays on the
 * internal side and is correlated through {@code instance}.
 */
public class DownstreamResponseExceptionMapper implements ResponseExceptionMapper<RuntimeException> {

    /** Downstream statuses whose body may carry the shared {@code FieldErrors} payload. */
    private static final Set<Integer> VALIDATION_STATUSES = Set.of(400, 422);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public boolean handles(int status, MultivaluedMap<String, Object> headers) {
        return status >= 400;
    }

    @Override
    public RuntimeException toThrowable(Response response) {
        int status = response.getStatus();
        return switch (status) {
            case 404 -> new DownstreamNotFoundException("Ressource inconnue en aval");
            case 409 -> new IdempotencyConflictException("Conflit d'etat en aval");
            // A 5xx is the called service failing, which the fault tolerance policies of plan 21
            // treat differently from a verdict on the request: hence the dedicated subclass.
            default -> status >= 500
                    ? new DownstreamServerException(status, "Reponse aval en echec: " + status)
                    : new DownstreamHttpException(status, "Reponse aval en echec: " + status,
                            VALIDATION_STATUSES.contains(status) ? relayedFieldErrors(response)
                                    : null);
        };
    }

    /**
     * Field-level errors of a downstream validation failure, read from either shape the internal
     * contracts use: the {@code FieldErrors} array
     * ({@code [{"field":...,"message":...,"code":...}]}), relayed entry by entry with its
     * {@code code}, or a problem {@code errors} object ({@code {"field":["message"]}}), flattened
     * to one entry per message. The messages are copied as received, in the order received.
     *
     * <p>An unreadable or absent body is not an error here: the status mapping stands on its own and
     * the relay is simply empty.
     */
    private static List<FieldErrors.FieldError> relayedFieldErrors(Response response) {
        String body = readBody(response);
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode errors = JSON.readTree(body).get("errors");
            if (errors == null) {
                return null;
            }
            List<FieldErrors.FieldError> relayed = new ArrayList<>();
            if (errors.isArray()) {
                errors.forEach(entry -> {
                    JsonNode field = entry.get("field");
                    JsonNode message = entry.get("message");
                    JsonNode code = entry.get("code");
                    if (field != null && message != null) {
                        relayed.add(new FieldErrors.FieldError(field.asText(), message.asText(),
                                code == null || code.isNull() ? null : code.asText()));
                    }
                });
            } else if (errors.isObject()) {
                for (Iterator<String> fields = errors.fieldNames(); fields.hasNext(); ) {
                    String field = fields.next();
                    errors.get(field).forEach(message ->
                            relayed.add(new FieldErrors.FieldError(field, message.asText(), null)));
                }
            }
            return relayed.isEmpty() ? null : relayed;
        } catch (RuntimeException | com.fasterxml.jackson.core.JacksonException e) {
            return null;
        }
    }

    private static String readBody(Response response) {
        try {
            return response.hasEntity() ? response.readEntity(String.class) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
