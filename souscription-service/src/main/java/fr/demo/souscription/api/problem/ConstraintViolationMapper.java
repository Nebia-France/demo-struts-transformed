package fr.demo.souscription.api.problem;

import fr.demo.souscription.observability.BusinessMetrics;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Violations de contrainte rendues en <b>400</b> {@code application/problem+json} (W4-T04).
 *
 * <p>Remplace le {@code response.sendError(400)} du source et l'accumulation d'{@code ActionErrors}
 * dans la requete : une entree de {@code errors} par champ rejete, avec la cle de message et le
 * code stable de {@code ERROR_VOCABULARY.md}.
 *
 * <p>L'ordre du tableau fait partie du contrat : le source accumulait les verdicts dans une
 * {@code LinkedHashMap}, l'ordre de restitution etait donc deterministe et la SPA place le focus
 * sur la premiere erreur. Les entrees sont donc triees selon l'ordre de declaration gele des
 * champs.
 */
@Provider
public class ConstraintViolationMapper implements ExceptionMapper<ConstraintViolationException> {

    /** Ordre de declaration gele des champs du contrat (ERROR_VOCABULARY.md). */
    private static final List<String> FIELD_ORDER = List.of("firstName", "lastName", "email",
            "birthDate", "initialAmount", "monthlyAmount", "durationYears", "accepted", "storage");

    /** Cle de message -> code stable, table de {@code ERROR_VOCABULARY.md}. */
    private static final Map<String, String> CODES = Map.ofEntries(
            Map.entry("subscriber.firstName.invalid", "FIRST_NAME_INVALID"),
            Map.entry("subscriber.lastName.invalid", "LAST_NAME_INVALID"),
            Map.entry("subscriber.email.invalid", "EMAIL_INVALID"),
            Map.entry("subscriber.birthDate.format", "BIRTH_DATE_FORMAT"),
            Map.entry("subscriber.birthDate.future", "BIRTH_DATE_FUTURE"),
            Map.entry("subscriber.birthDate.minor", "BIRTH_DATE_MINOR"),
            Map.entry("simulation.initialAmount.format", "INITIAL_AMOUNT_FORMAT"),
            Map.entry("simulation.initialAmount.range", "INITIAL_AMOUNT_RANGE"),
            Map.entry("simulation.monthlyAmount.format", "MONTHLY_AMOUNT_FORMAT"),
            Map.entry("simulation.monthlyAmount.range", "MONTHLY_AMOUNT_RANGE"),
            Map.entry("simulation.durationYears.range", "DURATION_YEARS_RANGE"),
            Map.entry("confirmation.accepted.required", "ACCEPTANCE_REQUIRED"));

    /**
     * Compteur des rejets de validation (W5-T03). Il est incremente ici et nulle part ailleurs :
     * tout chemin de rejet passe par ce mapper, le comptage est donc exact et sans double compte,
     * ce qu'une incrementation dans chaque ressource ne garantirait pas.
     */
    @Inject
    BusinessMetrics metrics;

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        metrics.validationFails().increment();
        Map<String, Problem.FieldError> byField = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            String field = fieldOf(violation);
            Problem.FieldError error = entry(field, violation.getMessage());
            // Un champ qui echoue sur plusieurs contraintes ne doit pas dependre de l'ordre
            // d'iteration de l'ensemble de violations : la cle la plus petite est retenue.
            byField.merge(error.field(), error, (existing, candidate) ->
                    candidate.message().compareTo(existing.message()) < 0 ? candidate : existing);
        }
        List<Problem.FieldError> errors = new ArrayList<>(byField.values());
        errors.sort(Comparator.comparingInt(error -> rank(error.field())));

        Problem problem = new Problem("urn:horizon:validation-failed", "horizon.validation.failed",
                400, null, null, Problems.correlationId(), errors);
        return Response.status(400).type(Problems.PROBLEM_JSON).entity(problem).build();
    }

    /**
     * Le chemin de propriete d'une validation de parametre de methode est prefixe du nom de la
     * methode et du parametre : seul le dernier noeud designe le champ du contrat.
     */
    private static String fieldOf(ConstraintViolation<?> violation) {
        String leaf = null;
        for (Path.Node node : violation.getPropertyPath()) {
            leaf = node.getName();
        }
        return leaf == null ? "" : leaf;
    }

    /**
     * {@code confirmationKey} absente ou non-uuid est l'equivalent cible du refus
     * {@code accepted=false} du recapitulatif source : champ {@code accepted}, code
     * {@code ACCEPTANCE_REQUIRED} (ERROR_VOCABULARY.md).
     */
    private static Problem.FieldError entry(String field, String message) {
        if ("confirmationKey".equals(field)) {
            return new Problem.FieldError("accepted", "confirmation.accepted.required",
                    "ACCEPTANCE_REQUIRED");
        }
        String code = CODES.get(message);
        if (code != null) {
            return new Problem.FieldError(field, message, code);
        }
        // Verdict sans cle de vocabulaire (message par defaut du validateur) : la cle et le code
        // sont derives du champ pour ne jamais renvoyer un libelle au client.
        return new Problem.FieldError(field, field + ".invalid", upperSnake(field) + "_INVALID");
    }

    private static int rank(String field) {
        int index = FIELD_ORDER.indexOf(field);
        return index < 0 ? FIELD_ORDER.size() : index;
    }

    private static String upperSnake(String field) {
        return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
    }
}
