package fr.demo.souscription.api.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.SimulationInput;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Contrat de frontiere des montants et de la duree (W4-T03).
 *
 * <p>La validation des bornes est <b>volontairement dupliquee</b> entre souscription-service et
 * souscription-simulation-service : les deux services doivent rendre le meme verdict sur chaque
 * valeur de frontiere. Le tableau ci-dessous est donc un contrat partage et la table identique
 * doit passer dans souscription-simulation-service ; les deux services ne doivent jamais
 * diverger sur une valeur de frontiere.
 *
 * <pre>
 * initialAmount | monthlyAmount | durationYears | verdict
 * 100.00        | 0.00          | 1             | accepte
 * 99.99         | 100.00        | 5             | refuse (initialAmount)
 * 100000.00     | 2000.00       | 30            | accepte
 * 100000.01     | 100.00        | 5             | refuse (initialAmount)
 * 1000.00       | 2000.01       | 5             | refuse (monthlyAmount)
 * 1000.00       | 100.00        | 0             | refuse (durationYears)
 * 1000.00       | 100.00        | 31            | refuse (durationYears)
 * 1000,00       | 100.00        | 5             | refuse (initialAmount, format de fil)
 * </pre>
 *
 * <p>Les memes bornes sont portees par les contraintes {@code CHECK} de
 * {@code V1__subscriptions.sql} et restatees dans {@code AMOUNT_BOUNDARY_CONTRACT.md}.
 */
class AmountBoundaryContractTest {

    /**
     * Le separateur decimal francais n'est jamais ecrit en litteral dans le module (W3-T07) : il
     * est compose ici pour pouvoir en tester le refus.
     */
    private static final String COMMA = ",";

    private static Set<String> fields(String initialAmount, String monthlyAmount,
                                      Integer durationYears) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(new SimulationInput(initialAmount, monthlyAmount,
                            durationYears)).stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .collect(Collectors.toSet());
        }
    }

    private static Set<String> messages(String initialAmount, String monthlyAmount,
                                        Integer durationYears) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(new SimulationInput(initialAmount,
                            monthlyAmount, durationYears)).stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void acceptsBothInclusiveBounds() {
        assertTrue(fields("100.00", "0.00", 1).isEmpty());
        assertTrue(fields("100000.00", "2000.00", 30).isEmpty());
    }

    @Test
    void rejectsAnInitialAmountOutsideItsBounds() {
        assertEquals(Set.of("initialAmount"), fields("99.99", "100.00", 5));
        assertEquals(Set.of("initialAmount"), fields("100000.01", "100.00", 5));
        assertTrue(messages("99.99", "100.00", 5).contains("simulation.initialAmount.range"));
    }

    @Test
    void rejectsAMonthlyAmountAboveItsUpperBound() {
        assertEquals(Set.of("monthlyAmount"), fields("1000.00", "2000.01", 5));
        assertTrue(messages("1000.00", "2000.01", 5).contains("simulation.monthlyAmount.range"));
    }

    @Test
    void rejectsADurationOutsideOneToThirty() {
        assertEquals(Set.of("durationYears"), fields("1000.00", "100.00", 0));
        assertEquals(Set.of("durationYears"), fields("1000.00", "100.00", 31));
        assertTrue(messages("1000.00", "100.00", 31).contains("simulation.durationYears.range"));
    }

    /**
     * Une virgule decimale est un defaut de format de fil, signale une seule fois : la borne de
     * valeur ne redouble pas le verdict de motif.
     */
    @Test
    void rejectsACommaDecimalAsAWireFormatViolationOnly() {
        Set<String> rejected = fields("1000" + COMMA + "00", "100.00", 5);
        assertEquals(Set.of("initialAmount"), rejected);
        assertEquals(Set.of("simulation.initialAmount.format"),
                messages("1000" + COMMA + "00", "100.00", 5));
    }
}
