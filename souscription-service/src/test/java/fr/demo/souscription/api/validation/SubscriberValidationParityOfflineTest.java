package fr.demo.souscription.api.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.SubscriberInput;
import fr.demo.souscription.api.mapper.ModelMapper;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Parite de verdict entre les contraintes declaratives de {@code SubscriberInput} et les regles
 * imperatives de {@code service/InputValidator} (plan 20 - verification de parite).
 *
 * <p>Chaque cas compare un verdict, pas une implementation : bornes 1-80 des noms, rejet des
 * caracteres de controle ISO, borne 254 et expression reguliere du source sur l'adresse
 * electronique, calendrier strict, date future et frontiere de majorite inclusive evaluee sur
 * {@code Europe/Paris} avec une horloge fixe.
 */
class SubscriberValidationParityOfflineTest {

    /** 2024-05-04 a Paris : la frontiere de majorite du source tombe sur 2006-05-04. */
    private static final Clock FIXED_2024_05_04 =
            Clock.fixed(Instant.parse("2024-05-04T09:00:00Z"), ZoneId.of("Europe/Paris"));

    private static final Clock FIXED_2024_05_03 =
            Clock.fixed(Instant.parse("2024-05-03T09:00:00Z"), ZoneId.of("Europe/Paris"));

    private static Set<String> fields(SubscriberInput input, Clock clock) {
        try (ValidatorFactory factory = Validation.byDefaultProvider().configure()
                .constraintValidatorFactory(new FixedClockValidatorFactory(clock))
                .buildValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(input).stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .collect(Collectors.toSet());
        }
    }

    private static Set<String> messages(SubscriberInput input, Clock clock) {
        try (ValidatorFactory factory = Validation.byDefaultProvider().configure()
                .constraintValidatorFactory(new FixedClockValidatorFactory(clock))
                .buildValidatorFactory()) {
            return factory.getValidator().validate(input).stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.toSet());
        }
    }

    private static SubscriberInput withName(String firstName) {
        return new SubscriberInput(firstName, "Martin", "claire@example.test", "2000-02-29");
    }

    private static SubscriberInput withEmail(String email) {
        return new SubscriberInput("Claire", "Martin", email, "2000-02-29");
    }

    private static SubscriberInput withBirthDate(String birthDate) {
        return new SubscriberInput("Claire", "Martin", "claire@example.test", birthDate);
    }

    @Test
    void nameBoundsAndControlChars() {
        assertFalse(fields(withName("N".repeat(80)), FIXED_2024_05_04).contains("firstName"),
                "un nom de 80 caracteres est accepte par le source");
        assertTrue(fields(withName("N".repeat(81)), FIXED_2024_05_04).contains("firstName"),
                "81 caracteres depasse la borne du source");
        assertTrue(fields(withName(""), FIXED_2024_05_04).contains("firstName"));
        assertTrue(fields(withName("Jean\u0007Demo"), FIXED_2024_05_04).contains("firstName"));
        assertTrue(fields(withName("Jean\nDemo"), FIXED_2024_05_04).contains("firstName"));
        // Les apostrophes, traits d'union et accents restaient valides dans le source.
        assertFalse(fields(withName("Jean-Édouard O'Martin"), FIXED_2024_05_04)
                .contains("firstName"));
    }

    @Test
    void emailBoundAndSourceRegexVerdicts() {
        assertFalse(fields(withEmail("a".repeat(241) + "@example.test"), FIXED_2024_05_04)
                .contains("email"), "254 caracteres exactement sont acceptes");
        assertTrue(fields(withEmail("a".repeat(242) + "@example.test"), FIXED_2024_05_04)
                .contains("email"), "255 caracteres depassent la borne du source");

        for (String accepted : new String[] {"claire@example.test", "c.l-a+ire@sub.example.test",
                "CLAIRE@EXAMPLE.TEST"}) {
            assertFalse(fields(withEmail(accepted), FIXED_2024_05_04).contains("email"),
                    "doit etre acceptee : " + accepted);
        }
        // Verdicts de rejet du source, dont les formes sans point et les chevrons.
        for (String rejected : new String[] {"claire", "claire@example", "a@b",
                "claire@exa mple.test", "cla ire@example.test",
                "<claire>@example.test", "claire@<example>.test", "@example.test",
                "claire@.test", "claire@example."}) {
            assertTrue(fields(withEmail(rejected), FIXED_2024_05_04).contains("email"),
                    "doit etre refusee : " + rejected);
        }
    }

    @Test
    void minimumAgeBoundary() {
        assertFalse(fields(withBirthDate("2006-05-04"), FIXED_2024_05_04).contains("birthDate"),
                "le jour exact du 18e anniversaire est eligible dans le source");
        assertTrue(fields(withBirthDate("2006-05-05"), FIXED_2024_05_04).contains("birthDate"),
                "la veille des 18 ans reste mineure");
        assertFalse(fields(withBirthDate("2006-05-03"), FIXED_2024_05_04).contains("birthDate"));
        // Jour bissextile : arithmetique LocalDate, verdict rendu sans exception.
        assertTrue(fields(withBirthDate("2008-02-29"), FIXED_2024_05_04).contains("birthDate"),
                "ne le 2008-02-29, le souscripteur est mineur au 2024-05-04");
        assertFalse(fields(withBirthDate("2004-02-29"), FIXED_2024_05_04).contains("birthDate"),
                "ne le 2004-02-29, le souscripteur est majeur au 2024-05-04");
    }

    @Test
    void validatorUsesInjectedClock() {
        assertFalse(fields(withBirthDate("2006-05-04"), FIXED_2024_05_04).contains("birthDate"));
        assertTrue(fields(withBirthDate("2006-05-04"), FIXED_2024_05_03).contains("birthDate"),
                "le verdict doit changer avec l'horloge injectee");
    }

    @Test
    void impossibleDateIsRejected() {
        assertTrue(fields(withBirthDate("2023-02-31"), FIXED_2024_05_04).contains("birthDate"),
                "2023-02-31 est refusee, jamais decalee en 2023-03-03");
        assertTrue(messages(withBirthDate("2023-02-31"), FIXED_2024_05_04)
                .contains("subscriber.birthDate.format"));
        assertTrue(fields(withBirthDate("2001-02-29"), FIXED_2024_05_04).contains("birthDate"));
        assertTrue(fields(withBirthDate("04/05/2006"), FIXED_2024_05_04).contains("birthDate"));
        assertTrue(fields(withBirthDate("2006-5-4"), FIXED_2024_05_04).contains("birthDate"));
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        SubscriberInput padded = new SubscriberInput("  Claire  ", "  Martin  ",
                "  claire@example.test  ", " 2006-05-04 ");

        assertTrue(fields(padded, FIXED_2024_05_04).isEmpty(),
                "les espaces superflus ne changent aucun verdict");
        assertEquals("Claire", ModelMapper.toDomain(padded).firstName());
        assertEquals("Martin", ModelMapper.toDomain(padded).lastName());
        assertEquals("claire@example.test", ModelMapper.toDomain(padded).email());

        // La borne de 80 porte sur la valeur normalisee, comme dans le source.
        assertFalse(fields(withName("  " + "N".repeat(80) + "  "), FIXED_2024_05_04)
                .contains("firstName"));
        assertTrue(fields(withName("   "), FIXED_2024_05_04).contains("firstName"),
                "un nom fait uniquement d'espaces est refuse");
    }

    @Test
    void futureAndMinorAreDistinctMessages() {
        Set<String> future = messages(withBirthDate("2099-01-01"), FIXED_2024_05_04);
        Set<String> minor = messages(withBirthDate("2006-05-05"), FIXED_2024_05_04);

        assertTrue(future.contains("subscriber.birthDate.future"));
        assertTrue(minor.contains("subscriber.birthDate.minor"));
        assertNotEquals(future, minor, "les deux verdicts de date restent distincts");
    }

    /** Pose l'horloge fixe dans les validateurs instancies hors CDI. */
    private record FixedClockValidatorFactory(Clock clock) implements ConstraintValidatorFactory {

        @Override
        public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
            try {
                T instance = key.getDeclaredConstructor().newInstance();
                for (Field field : key.getDeclaredFields()) {
                    if (field.getType() == Clock.class) {
                        field.setAccessible(true);
                        field.set(instance, clock);
                    }
                }
                return instance;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public void releaseInstance(ConstraintValidator<?, ?> instance) {
            // sans etat a liberer
        }
    }
}
