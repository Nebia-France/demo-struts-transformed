package fr.demo.souscription.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.SimulationInput;
import fr.demo.souscription.api.dto.SubscriberInput;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * W3-T07, variante sans conteneur : les contraintes de format de fil portees par les DTO sont
 * verifiees directement par le validateur, sans base de donnees. Garantit qu'un montant a
 * virgule decimale ou une date au format local est une violation de contrainte (donc un 400) et
 * non une erreur de parsing non geree (500).
 */
class WireFormatConstraintTest {

    private static final String COMMA = ",";

    private static Set<String> violations(Object dto) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(dto).stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void aCommaDecimalAmountViolatesTheWireFormat() {
        Set<String> rejected =
                violations(new SimulationInput("1000" + COMMA + "00", "100.00", 10));

        assertEquals(Set.of("initialAmount"), rejected);
    }

    @Test
    void aThousandsSeparatorViolatesTheWireFormat() {
        Set<String> rejected = violations(new SimulationInput("1 000.00", "100.00", 10));

        assertTrue(rejected.contains("initialAmount"));
    }

    @Test
    void aScalePreservingAmountIsAccepted() {
        assertTrue(violations(new SimulationInput("1000.00", "100.00", 10)).isEmpty());
    }

    @Test
    void aMissingAmountIsAConstraintViolation() {
        Set<String> rejected = violations(new SimulationInput(null, null, null));

        assertEquals(Set.of("initialAmount", "monthlyAmount", "durationYears"), rejected);
    }

    @Test
    void aDurationOutOfTheFrozenRangeIsRejected() {
        assertTrue(violations(new SimulationInput("1000.00", "100.00", 31))
                .contains("durationYears"));
        assertTrue(violations(new SimulationInput("1000.00", "100.00", 0))
                .contains("durationYears"));
    }

    @Test
    void aLocalFormattedBirthDateIsRejected() {
        assertTrue(violations(new SubscriberInput("Claire", "Martin",
                "claire.martin@example.com", "12/05/1990")).contains("birthDate"));
    }

    @Test
    void anIsoBirthDateIsAccepted() {
        assertFalse(violations(new SubscriberInput("Claire", "Martin",
                "claire.martin@example.com", "1990-05-12")).contains("birthDate"));
    }
}
