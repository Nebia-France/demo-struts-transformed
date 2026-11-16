package fr.demo.souscription.api.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.SimulationInput;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Memes verdicts de bornes que {@link AmountBoundaryParityTest}, rejoues sans runtime de
 * conteneurs : la fabrique de validation est construite directement, aucune base n'est requise
 * (plan 21 - verification de parite).
 *
 * <p>Les deux cas pilotes par HTTP (400 en {@code application/problem+json}) restent couverts par
 * {@link AmountBoundaryProblemJsonTest} et par {@code AmountBoundaryParityTest}, qui ont besoin de
 * Dev Services PostgreSQL.
 */
class AmountBoundaryParityOfflineTest {

    /** Le separateur decimal francais n'est jamais ecrit en litteral dans le module (W3-T07). */
    private static final String COMMA = ",";

    private static Set<ConstraintViolation<SimulationInput>> violations(String initial,
                                                                       String monthly,
                                                                       Integer years) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(new SimulationInput(initial, monthly, years));
        }
    }

    private static boolean accepts(String initial, String monthly, Integer years) {
        return violations(initial, monthly, years).isEmpty();
    }

    private static String rejectedField(String initial, String monthly, Integer years) {
        return violations(initial, monthly, years).stream()
                .map(v -> v.getPropertyPath().toString())
                .findFirst()
                .orElse("<accepted>");
    }

    @Test
    void theEightContractRows() {
        assertTrue(accepts("100.00", "0.00", 1), "row 1");
        assertEquals("initialAmount", rejectedField("99.99", "100.00", 5), "row 2");
        assertTrue(accepts("100000.00", "2000.00", 30), "row 3");
        assertEquals("initialAmount", rejectedField("100000.01", "100.00", 5), "row 4");
        assertEquals("monthlyAmount", rejectedField("1000.00", "2000.01", 5), "row 5");
        assertEquals("durationYears", rejectedField("1000.00", "100.00", 0), "row 6");
        assertEquals("durationYears", rejectedField("1000.00", "100.00", 31), "row 7");
        assertEquals("initialAmount", rejectedField("1000" + COMMA + "00", "100.00", 5), "row 8");
    }

    @Test
    void boundsAreInclusive() {
        assertTrue(accepts("100.00", "100.00", 5));
        assertTrue(accepts("100000.00", "100.00", 5));
        assertTrue(accepts("1000.00", "0.00", 5));
        assertTrue(accepts("1000.00", "2000.00", 5));
        assertTrue(accepts("1000.00", "100.00", 1));
        assertTrue(accepts("1000.00", "100.00", 30));
    }

    @Test
    void rejectionNamesTheOffendingField() {
        assertEquals("initialAmount", rejectedField("50.00", "100.00", 5));
        assertEquals("monthlyAmount", rejectedField("1000.00", "5000.00", 5));
        assertEquals("durationYears", rejectedField("1000.00", "100.00", 99));
    }

    @Test
    void patternViolationIsReportedOnce() {
        long onInitial = violations("abc", "100.00", 5).stream()
                .filter(v -> v.getPropertyPath().toString().equals("initialAmount"))
                .count();
        assertEquals(1, onInitial,
                "une valeur qui echoue deja a @Pattern ne doit pas produire un second verdict de "
                + "borne");
    }

    @Test
    void durationRejectsSourceEdgeCases() {
        assertFalse(accepts("1000.00", "100.00", 0));
        assertFalse(accepts("1000.00", "100.00", 31));
        assertFalse(accepts("1000.00", "100.00", null), "durationYears is @NotNull");
    }
}
