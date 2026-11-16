package fr.demo.souscription.api.validation;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Rejette toute chaine contenant un caractere de controle ISO (W4-T01).
 *
 * <p>Porte la troisieme condition de {@code service/InputValidator.validateName} du source
 * ({@code value.codePoints().anyMatch(Character::isISOControl)}) sous forme declarative. La
 * longueur et l'obligation de saisie restent portees par {@code @Size} et {@code @NotBlank}.
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER,
        ElementType.METHOD})
@Retention(RUNTIME)
@Constraint(validatedBy = NoControlCharsValidator.class)
public @interface NoControlChars {

    String message() default "{horizon.subscriber.noControlChars}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
