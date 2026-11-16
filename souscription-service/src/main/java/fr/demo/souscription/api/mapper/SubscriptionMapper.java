package fr.demo.souscription.api.mapper;

import fr.demo.souscription.api.dto.ProductSummary;
import fr.demo.souscription.api.dto.SubscriptionConfirmation;
import fr.demo.souscription.domain.Product;
import fr.demo.souscription.domain.Simulation;
import fr.demo.souscription.domain.Subscriber;
import fr.demo.souscription.domain.Subscription;
import fr.demo.souscription.domain.SubscriptionEntity;
import java.time.format.DateTimeFormatter;

/**
 * Conversions souscription confirmee : entite JPA <-> domaine (composant cible
 * subscriptionDtos). Toutes les colonnes de V1__subscriptions.sql sont NOT NULL.
 */
public class SubscriptionMapper {

    private SubscriptionMapper() {
    }

    public static Subscription toDomain(SubscriptionEntity entity) {
        if (entity == null) {
            return null;
        }
        return new Subscription(entity.reference, entity.confirmationKey,
                new Product(entity.productCode, entity.productName, entity.productProfile,
                        entity.annualRate),
                new Subscriber(entity.firstName, entity.lastName, entity.email, entity.birthDate),
                new Simulation(entity.initialAmount, entity.monthlyAmount, entity.durationYears,
                        entity.contributed, entity.finalCapital),
                entity.createdAt);
    }

    /**
     * Corps gele SubscriptionConfirmation : les champs produit sont lus sur la ligne
     * denormalisee, aucune lecture du catalogue n'est declenchee.
     */
    public static SubscriptionConfirmation toConfirmation(SubscriptionEntity entity) {
        if (entity == null) {
            return null;
        }
        Subscription subscription = toDomain(entity);
        Product product = subscription.product();
        return new SubscriptionConfirmation(subscription.reference().toString(),
                new ProductSummary(product.code(), product.name(), product.profile(),
                        ModelMapper.rate(product.annualRate())),
                ModelMapper.toDto(subscription.subscriber()),
                ModelMapper.toDto(subscription.simulation()),
                subscription.createdAt() == null
                        ? null
                        : DateTimeFormatter.ISO_INSTANT.format(subscription.createdAt()));
    }

    public static SubscriptionEntity toEntity(Subscription subscription) {
        if (subscription == null) {
            return null;
        }
        SubscriptionEntity entity = new SubscriptionEntity();
        entity.reference = subscription.reference();
        entity.confirmationKey = subscription.confirmationKey();
        entity.productCode = subscription.product().code();
        entity.productName = subscription.product().name();
        entity.productProfile = subscription.product().profile();
        entity.annualRate = subscription.product().annualRate();
        entity.firstName = subscription.subscriber().firstName();
        entity.lastName = subscription.subscriber().lastName();
        entity.email = subscription.subscriber().email();
        entity.birthDate = subscription.subscriber().birthDate();
        entity.initialAmount = subscription.simulation().initialAmount();
        entity.monthlyAmount = subscription.simulation().monthlyAmount();
        entity.durationYears = subscription.simulation().durationYears();
        entity.contributed = subscription.simulation().contributed();
        entity.finalCapital = subscription.simulation().finalCapital();
        entity.createdAt = subscription.createdAt();
        return entity;
    }
}
