package fr.demo.souscription.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;

/**
 * Rend {@code true} pour {@code null} (role de {@code @NotNull}) et pour une valeur non
 * convertible en {@code BigDecimal} (role de {@code @Pattern}) : le verdict de forme n'est pas
 * duplique en verdict de borne.
 */
public class DecimalRangeValidator implements ConstraintValidator<DecimalRange, String> {

    private BigDecimal min;
    private BigDecimal max;

    @Override
    public void initialize(DecimalRange constraint) {
        this.min = new BigDecimal(constraint.min());
        this.max = new BigDecimal(constraint.max());
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(value);
        } catch (NumberFormatException malformed) {
            return true;
        }
        return amount.compareTo(min) >= 0 && amount.compareTo(max) <= 0;
    }
}
