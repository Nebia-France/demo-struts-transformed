package fr.demo.souscription.simulation.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Comparator;
import java.util.List;

/**
 * Remplace le rendu JSP de la map d'erreurs (clés legacy initial / monthly / years) par un corps
 * application/problem+json : une entrée par champ en échec, dans l'ordre de déclaration comme le
 * LinkedHashMap legacy, avec le message legacy inchangé.
 */
@Provider
public class ValidationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    /** Ordre de déclaration de SimulationRequest = ordre des entrées du LinkedHashMap legacy. */
    private static final List<String> FIELD_ORDER =
            List.of("initialAmount", "monthlyAmount", "durationYears", "annualRate");

    @Context
    HttpHeaders headers;

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        List<FieldErrors.FieldError> errors = exception.getConstraintViolations().stream()
                .sorted(Comparator.comparingInt(ValidationExceptionMapper::declarationIndex)
                        .thenComparing(v -> leafNode(v.getPropertyPath())))
                .map(v -> new FieldErrors.FieldError(leafNode(v.getPropertyPath()), v.getMessage(), null))
                .toList();
        return ProblemJson.response(400, "Validation failed", null, errors, headers);
    }

    private static int declarationIndex(ConstraintViolation<?> violation) {
        int index = FIELD_ORDER.indexOf(leafNode(violation.getPropertyPath()));
        return index < 0 ? FIELD_ORDER.size() : index;
    }

    /** Le consommateur ne sait pas corréler computeSimulation.arg0.initialAmount : on ne garde que la feuille. */
    private static String leafNode(Path path) {
        String node = null;
        for (Path.Node current : path) {
            node = current.getName();
        }
        return node;
    }
}
