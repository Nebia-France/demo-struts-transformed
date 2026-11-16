package fr.demo.souscription.api.validation;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Borne de valeur inclusive d'un montant transporte en chaine d'echelle fixe (W4-T03).
 *
 * <p>Porte les bornes de {@code service/InputValidator.simulationErrors} du source
 * (versement initial 100-100000, mensualite 0-2000). Les bornes sont declarees en chaines et
 * comparees apres conversion en {@code java.math.BigDecimal} : le fil gele serialise les montants
 * en chaines pour preserver l'echelle, aucune conversion en {@code double} n'intervient.
 *
 * <p>La forme du fil reste le role de {@code @Pattern} : une valeur non conforme au motif est
 * acceptee ici pour que la violation ne soit signalee qu'une seule fois.
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER,
        ElementType.METHOD})
@Retention(RUNTIME)
@Constraint(validatedBy = DecimalRangeValidator.class)
public @interface DecimalRange {

    /** Borne basse inclusive, en notation decimale a point. */
    String min();

    /** Borne haute inclusive, en notation decimale a point. */
    String max();

    String message() default "{horizon.simulation.amount.range}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
