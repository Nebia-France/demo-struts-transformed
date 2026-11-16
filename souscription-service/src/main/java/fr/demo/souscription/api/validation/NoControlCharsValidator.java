package fr.demo.souscription.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Rend {@code true} pour {@code null} : l'obligation de saisie est le role de {@code @NotBlank},
 * exactement comme dans le source ou les deux conditions etaient distinctes.
 */
public class NoControlCharsValidator implements ConstraintValidator<NoControlChars, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value.codePoints().noneMatch(Character::isISOControl);
    }
}
