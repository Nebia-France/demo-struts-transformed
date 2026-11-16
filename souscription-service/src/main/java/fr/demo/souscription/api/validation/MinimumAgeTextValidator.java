package fr.demo.souscription.api.validation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Variante chaine du meme verdict : {@code SubscriberInput.birthDate} reste une chaine ISO-8601
 * sur le fil (type gele du contrat, verifie par {@code ModelSplitParityTest}).
 *
 * <p>La resolution stricte du calendrier est celle de {@code LocalDate.parse} : {@code 2023-02-31}
 * est rejete et jamais decale silencieusement, comme le {@code ResolverStyle.STRICT} du source.
 * Une date non resoluble produit la cle de format, distincte des deux verdicts de date.
 */
@ApplicationScoped
public class MinimumAgeTextValidator implements ConstraintValidator<MinimumAge, String> {

    private static final String FORMAT_TEMPLATE = "{horizon.subscriber.birthDate.format}";

    @Inject
    Clock clock = Clock.system(ZoneId.of("Europe/Paris"));

    private int age;

    @Override
    public void initialize(MinimumAge annotation) {
        this.age = annotation.value();
    }

    @Override
    public boolean isValid(String raw, ConstraintValidatorContext context) {
        if (raw == null) {
            return true;
        }
        LocalDate birth;
        try {
            birth = LocalDate.parse(raw);
        } catch (DateTimeParseException notACalendarDate) {
            return MinimumAgeValidator.reject(context, FORMAT_TEMPLATE);
        }
        return MinimumAgeValidator.isValid(birth, age, clock, context);
    }
}
