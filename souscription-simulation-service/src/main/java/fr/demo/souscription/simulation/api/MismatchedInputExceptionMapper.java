package fr.demo.souscription.simulation.api;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.List;

/**
 * Un champ au mauvais type JSON (durationYears = 5.5, montant en chaîne non numérique) est une
 * erreur de champ, pas un corps illisible : la réponse est un 400 application/problem+json
 * portant le nom de la propriété en échec, comme pour une violation de contrainte.
 *
 * <p>Ce mapper est plus spécifique que celui fourni par l'extension Quarkus REST Jackson, dont le
 * corps par défaut ({@code {"objectName":"Class","attributeName":...,"line":1,"column":62}})
 * expose la position dans le flux et le nom de classe en application/json, ce qui viole à la fois
 * le média type contractuel et NFG-008.
 */
@Provider
public class MismatchedInputExceptionMapper implements ExceptionMapper<MismatchedInputException> {

    @Context
    HttpHeaders headers;

    @Override
    public Response toResponse(MismatchedInputException exception) {
        String field = leafField(exception);
        List<FieldErrors.FieldError> errors = field == null ? null
                : List.of(new FieldErrors.FieldError(field, message(field), null));
        return ProblemJson.response(400, "Validation failed", null, errors, headers);
    }

    /** Message legacy du champ concerné ; aucun détail Jackson n'est repris. */
    private static String message(String field) {
        return switch (field) {
            case "durationYears" -> "La durée doit être un nombre entier de 1 à 30 ans.";
            case "initialAmount" -> "Le versement initial doit être compris entre 100,00 et"
                    + " 100 000,00 €. Utilisez au maximum deux décimales, sans séparateur de milliers.";
            case "monthlyAmount" -> "La mensualité doit être comprise entre 0,00 et 2 000,00 €."
                    + " Utilisez au maximum deux décimales, sans séparateur de milliers.";
            default -> "Paramètres de simulation invalides";
        };
    }

    private static String leafField(MismatchedInputException exception) {
        String field = null;
        for (com.fasterxml.jackson.databind.JsonMappingException.Reference reference : exception.getPath()) {
            if (reference.getFieldName() != null) {
                field = reference.getFieldName();
            }
        }
        return field;
    }
}
