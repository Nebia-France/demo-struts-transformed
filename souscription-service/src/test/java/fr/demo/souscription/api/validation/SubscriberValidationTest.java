package fr.demo.souscription.api.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.SimulationInput;
import fr.demo.souscription.api.dto.SubscriberInput;
import fr.demo.souscription.api.mapper.ModelMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.Validator;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Parite de verdict avec {@code src/test/.../service/InputValidatorTest} (W4-T01).
 *
 * <p>Chaque cas souscripteur du test source est represente ici avec le <b>meme verdict</b>, sur la
 * meme horloge fixe (2026-11-07). Seule la representation de la date change : le fil cible est
 * ISO-8601 et non jour/mois/annee, ecart gele par {@code SubscriberInput.json}.
 *
 * <p>Les trois cas de montant et de duree du test source (W8-T03) sont representes ici contre les
 * contraintes de {@code SimulationInput} : la virgule decimale francaise n'est plus un format
 * admis sur le fil (ecart gele par {@code AMOUNT_BOUNDARY_CONTRACT.md}), elle est donc refusee au
 * lieu d'etre convertie.
 */
class SubscriberValidationTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneId.of("Europe/Paris"));

    private static Set<String> fields(SubscriberInput input) {
        try (ValidatorFactory factory = Validation.byDefaultProvider().configure()
                .constraintValidatorFactory(new FixedClockValidatorFactory())
                .buildValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(input).stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .collect(Collectors.toSet());
        }
    }

    private static Set<String> fields(SimulationInput input) {
        try (ValidatorFactory factory = Validation.byDefaultProvider().configure()
                .constraintValidatorFactory(new FixedClockValidatorFactory())
                .buildValidatorFactory()) {
            return factory.getValidator().validate(input).stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .collect(Collectors.toSet());
        }
    }

    private static Set<String> messages(SubscriberInput input) {
        try (ValidatorFactory factory = Validation.byDefaultProvider().configure()
                .constraintValidatorFactory(new FixedClockValidatorFactory())
                .buildValidatorFactory()) {
            return factory.getValidator().validate(input).stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.toSet());
        }
    }

    private static SubscriberInput subscriber() {
        return new SubscriberInput("Camille <Démo>", "O'Martin", "camille@example.test",
                "2008-11-07");
    }

    private static SubscriberInput withBirthDate(String birthDate) {
        SubscriberInput base = subscriber();
        return new SubscriberInput(base.firstName(), base.lastName(), base.email(), birthDate);
    }

    @Test
    void acceptsExactlyEighteenAndPreservesEscapableText() {
        assertTrue(fields(subscriber()).isEmpty());
        assertEquals("Camille <Démo>", ModelMapper.toDomain(subscriber()).firstName());
    }

    @Test
    void refusesMinorFutureAndMalformedBirthDates() {
        // Equivalents ISO des cas source 08/11/2008, 01/01/2099, 31/02/1980, 29/02/2001,
        // la chaine vide et les formes non ISO (1/1/1980, format local).
        for (String date : new String[] {"2008-11-08", "2099-01-01", "1980-02-31", "2001-02-29",
                "", "1/1/1980", "07/11/2008"}) {
            assertTrue(fields(withBirthDate(date)).contains("birthDate"),
                    "birthDate doit etre refusee : " + date);
        }
    }

    @Test
    void keepsTheThreeBirthDateVerdictsDistinct() {
        assertTrue(messages(withBirthDate("2099-01-01")).contains("subscriber.birthDate.future"));
        assertTrue(messages(withBirthDate("2008-11-08")).contains("subscriber.birthDate.minor"));
        assertTrue(messages(withBirthDate("1980-02-31")).contains("subscriber.birthDate.format"));
    }

    @Test
    void acceptsLeapDayAndRejectsControlCharactersAndBadEmails() {
        assertTrue(fields(withBirthDate("2000-02-29")).isEmpty());

        SubscriberInput invalid = new SubscriberInput("Jean\nDémo", "O'Martin",
                "x<script>@example.test", "2000-02-29");
        assertTrue(fields(invalid).contains("firstName"));
        assertTrue(fields(invalid).contains("email"));
    }

    @Test
    void enforcesTheSourceNameAndEmailBounds() {
        assertTrue(fields(new SubscriberInput("  ", "O'Martin", "camille@example.test",
                "2000-02-29")).contains("firstName"));
        assertTrue(fields(new SubscriberInput("Camille", "N".repeat(81),
                "camille@example.test", "2000-02-29")).contains("lastName"));
        assertTrue(fields(new SubscriberInput("Camille", "O'Martin",
                "a".repeat(244) + "@example.test", "2000-02-29")).contains("email"));
        assertFalse(fields(new SubscriberInput("C", "N".repeat(80), "camille@example.test",
                "2000-02-29")).contains("lastName"));
    }

    @Test
    void stripsSurroundingWhitespaceFromSubscriberData() {
        SubscriberInput padded = new SubscriberInput("  Camille  ", "  O'Martin  ",
                "  camille@example.test  ", " 2008-11-07 ");

        assertEquals("Camille", ModelMapper.toDomain(padded).firstName());
        assertEquals("O'Martin", ModelMapper.toDomain(padded).lastName());
        assertEquals("camille@example.test", ModelMapper.toDomain(padded).email());
    }

    /**
     * Methode portee : {@code parsesFrenchAndDotDecimalsAndAcceptsInclusiveBounds}. Le point
     * decimal et les bornes inclusives restent admis ; la virgule francaise est refusee par le
     * motif du fil au lieu d'etre convertie.
     */
    @Test
    void acceptsDotDecimalsAndInclusiveBoundsAndRefusesTheFrenchComma() {
        assertTrue(fields(new SimulationInput("100.01", "0.00", 1)).isEmpty());
        assertEquals(Set.of("initialAmount"), fields(new SimulationInput("100,01", "0.00", 1)));
        assertTrue(fields(new SimulationInput("100000.00", "2000.00", 30)).isEmpty());
        assertTrue(fields(new SimulationInput("100.00", "0.00", 1)).isEmpty());
    }

    /** Methode portee : {@code refusesAmbiguousMoney}, memes valeurs que le source. */
    @Test
    void refusesAmbiguousMoney() {
        for (String value : new String[] {"-1", "1e3", "100,001", "1 000,00", "1.000,00", "NaN",
                "", "100.", "100,", "1000000", " 100"}) {
            assertTrue(fields(new SimulationInput(value, "0.00", 1)).contains("initialAmount"),
                    "montant ambigu refuse : '" + value + "'");
            assertTrue(fields(new SimulationInput("1000.00", value, 1)).contains("monthlyAmount"),
                    "mensualite ambigue refusee : '" + value + "'");
        }
    }

    /**
     * Methode portee : {@code enforcesFinancialLimitsAndIntegerYears}. Les trois limites sont
     * refusees ensemble. La duree non entiere du source (1.5) n'est plus representable :
     * {@code durationYears} est un {@code Integer} ; la limite de duree est exercee par 31.
     */
    @Test
    void enforcesFinancialLimitsAndIntegerYears() {
        assertEquals(Set.of("initialAmount", "monthlyAmount", "durationYears"),
                fields(new SimulationInput("99.99", "2000.01", 31)));
    }

    /**
     * Hors conteneur, l'horloge n'est pas injectee par CDI : la fabrique la pose explicitement
     * pour que la frontiere de majorite soit evaluee sur la meme date que le test source.
     */
    private static final class FixedClockValidatorFactory
            implements jakarta.validation.ConstraintValidatorFactory {

        @Override
        public <T extends jakarta.validation.ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
            try {
                T instance = key.getDeclaredConstructor().newInstance();
                for (Field field : key.getDeclaredFields()) {
                    if (field.getType() == Clock.class) {
                        field.setAccessible(true);
                        field.set(instance, FIXED);
                    }
                }
                return instance;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public void releaseInstance(jakarta.validation.ConstraintValidator<?, ?> instance) {
            // sans etat a liberer
        }
    }
}
