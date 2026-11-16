package fr.demo.souscription.api.validation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Verdicts de date portes a l'identique du source : d'abord le futur, puis la majorite.
 *
 * <p>L'horloge est injectee (Europe/Paris). La valeur par defaut du champ ne sert qu'aux
 * utilisations hors conteneur du validateur (fabrique {@code Validation.buildDefaultValidatorFactory}) :
 * sous CDI elle est remplacee par le producteur unique d'horloge.
 */
@ApplicationScoped
public class MinimumAgeValidator implements ConstraintValidator<MinimumAge, LocalDate> {

    static final String FUTURE_TEMPLATE = "{horizon.subscriber.birthDate.future}";

    @Inject
    Clock clock = Clock.system(ZoneId.of("Europe/Paris"));

    private int age;

    @Override
    public void initialize(MinimumAge annotation) {
        this.age = annotation.value();
    }

    @Override
    public boolean isValid(LocalDate birth, ConstraintValidatorContext context) {
        if (birth == null) {
            return true;
        }
        return isValid(birth, age, clock, context);
    }

    /**
     * Ordre des verdicts du source : une date future n'est jamais rapportee comme minorite.
     */
    static boolean isValid(LocalDate birth, int age, Clock clock,
                           ConstraintValidatorContext context) {
        LocalDate today = LocalDate.now(clock);
        if (birth.isAfter(today)) {
            return reject(context, FUTURE_TEMPLATE);
        }
        return !birth.isAfter(today.minusYears(age));
    }

    static boolean reject(ConstraintValidatorContext context, String template) {
        if (context != null) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(template).addConstraintViolation();
        }
        return false;
    }
}
