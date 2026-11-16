package fr.demo.souscription.api.validation;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Date de naissance stricte et majorite (W4-T02).
 *
 * <p>Porte les trois verdicts de date de {@code service/InputValidator.subscriberErrors} :
 * date non resoluble en calendrier strict, date dans le futur, souscripteur mineur. Les trois
 * restent distincts, comme le fige {@code ERROR_VOCABULARY.md} ({@code BIRTH_DATE_FORMAT},
 * {@code BIRTH_DATE_FUTURE}, {@code BIRTH_DATE_MINOR}) : les fusionner degraderait le scenario
 * <i>Date de naissance invalide ou non conforme</i>.
 *
 * <p>La frontiere de majorite est evaluee sur {@code Europe/Paris} via l'horloge injectee
 * (producteur {@code infrastructure/TimeProducer}), jamais via une horloge systeme implicite.
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER,
        ElementType.METHOD})
@Retention(RUNTIME)
@Constraint(validatedBy = {MinimumAgeValidator.class, MinimumAgeTextValidator.class})
public @interface MinimumAge {

    int value();

    String message() default "{horizon.subscriber.minimumAge}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
