package fr.demo.souscription.api.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * Frontiere de majorite sur horloge fixe Europe/Paris (W4-T02).
 */
class MinimumAgeValidatorTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneId.of("Europe/Paris"));

    private static MinimumAgeValidator validator() {
        MinimumAgeValidator validator = new MinimumAgeValidator();
        validator.clock = FIXED;
        validator.initialize(new MinimumAgeLiteral(18));
        return validator;
    }

    @Test
    void acceptsTheExactEighteenYearBoundary() {
        assertTrue(validator().isValid(LocalDate.of(2008, 11, 7), null));
    }

    @Test
    void acceptsTheDayBeforeTheBoundary() {
        assertTrue(validator().isValid(LocalDate.of(2008, 11, 6), null));
    }

    @Test
    void refusesTheDayAfterTheBoundary() {
        assertFalse(validator().isValid(LocalDate.of(2008, 11, 8), null));
    }

    @Test
    void acceptsALeapDayBirthDate() {
        assertTrue(validator().isValid(LocalDate.of(2000, 2, 29), null));
    }

    @Test
    void refusesAFutureBirthDate() {
        assertFalse(validator().isValid(LocalDate.of(2099, 1, 1), null));
    }

    /**
     * Instance d'annotation minimale : seule {@code value()} est lue par {@code initialize}.
     */
    private static final class MinimumAgeLiteral implements MinimumAge {

        private final int value;

        private MinimumAgeLiteral(int value) {
            this.value = value;
        }

        @Override
        public int value() {
            return value;
        }

        @Override
        public String message() {
            return "{horizon.subscriber.minimumAge}";
        }

        @Override
        public Class<?>[] groups() {
            return new Class<?>[0];
        }

        @Override
        public Class<? extends jakarta.validation.Payload>[] payload() {
            return newPayloadArray();
        }

        @SuppressWarnings("unchecked")
        private static Class<? extends jakarta.validation.Payload>[] newPayloadArray() {
            return (Class<? extends jakarta.validation.Payload>[]) new Class<?>[0];
        }

        @Override
        public Class<? extends java.lang.annotation.Annotation> annotationType() {
            return MinimumAge.class;
        }
    }
}
