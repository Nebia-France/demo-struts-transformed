package fr.demo.souscription.api.mapper;

import fr.demo.souscription.api.dto.ProductDetail;
import fr.demo.souscription.api.dto.SimulationResult;
import fr.demo.souscription.api.dto.SubscriberInput;
import fr.demo.souscription.domain.Product;
import fr.demo.souscription.domain.Simulation;
import fr.demo.souscription.domain.Subscriber;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Conversions elementaires domaine <-> DTO (composant cible subscriptionDtos).
 *
 * <p>Methodes statiques uniquement : aucun framework de mapping n'est ajoute. Les echelles
 * decimales sont celles des schemas geles (2 pour les montants, 6 pour le taux) ; la conversion
 * passe par {@link BigDecimal#setScale(int, RoundingMode)} et jamais par un {@code double}.
 */
public class ModelMapper {

    private static final int AMOUNT_SCALE = 2;
    private static final int RATE_SCALE = 6;

    private ModelMapper() {
    }

    public static String amount(BigDecimal value) {
        return value == null ? null : value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    public static BigDecimal amount(String value) {
        return value == null ? null : new BigDecimal(value).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    public static String rate(BigDecimal value) {
        return value == null ? null : value.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    public static SubscriberInput toDto(Subscriber subscriber) {
        if (subscriber == null) {
            return null;
        }
        return new SubscriberInput(subscriber.firstName(), subscriber.lastName(),
                subscriber.email(),
                subscriber.birthDate() == null
                        ? null
                        : subscriber.birthDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
    }

    public static Subscriber toDomain(SubscriberInput dto) {
        if (dto == null) {
            return null;
        }
        return new Subscriber(trimmed(dto.firstName()), trimmed(dto.lastName()),
                trimmed(dto.email()),
                dto.birthDate() == null ? null : LocalDate.parse(dto.birthDate().trim()));
    }

    /**
     * Normalisation explicite reprise du {@code trim()} de {@code service/InputValidator} :
     * les espaces superflus de la saisie du souscripteur ne sont jamais persistes. Elle est
     * portee par le mapper et non par un validateur, un validateur ne devant pas muter la valeur.
     */
    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    public static SimulationResult toDto(Simulation simulation) {
        if (simulation == null) {
            return null;
        }
        return new SimulationResult(amount(simulation.initialAmount()),
                amount(simulation.monthlyAmount()), simulation.durationYears(),
                amount(simulation.contributed()), amount(simulation.finalCapital()));
    }

    public static Simulation toDomain(SimulationResult dto) {
        if (dto == null) {
            return null;
        }
        return new Simulation(amount(dto.initialAmount()), amount(dto.monthlyAmount()),
                dto.durationYears() == null ? 0 : dto.durationYears(),
                amount(dto.contributed()), amount(dto.finalCapital()));
    }

    /**
     * Le catalogue est la source autoritaire de la description : elle n'est pas copiee dans le
     * brouillon persiste et doit donc etre fournie par l'appelant.
     */
    public static ProductDetail toDto(Product product, String description) {
        if (product == null) {
            return null;
        }
        return new ProductDetail(product.code(), product.name(), product.profile(), description,
                rate(product.annualRate()));
    }

    public static Product toDomain(ProductDetail dto) {
        if (dto == null) {
            return null;
        }
        return new Product(dto.code(), dto.name(), dto.profile(),
                dto.annualRate() == null ? null : new BigDecimal(dto.annualRate()));
    }
}
