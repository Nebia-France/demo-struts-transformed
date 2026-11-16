package fr.demo.souscription.api.mapper;

import fr.demo.souscription.api.dto.DraftView;
import fr.demo.souscription.domain.Draft;
import fr.demo.souscription.domain.Product;
import fr.demo.souscription.domain.Simulation;
import fr.demo.souscription.domain.Subscriber;
import fr.demo.souscription.domain.SubscriptionDraftEntity;
import java.time.Instant;

/**
 * Conversions brouillon : entite JPA <-> domaine <-> DTO (composant cible subscriptionDtos).
 *
 * <p>Methodes statiques, aucun framework de mapping. Les colonnes souscripteur et simulation
 * etant nullables, l'absence de valeur se traduit par un {@code null} de domaine, comme
 * {@code draft.getSubscriber() == null} dans la source.
 */
public class DraftMapper {

    private DraftMapper() {
    }

    public static Draft toDomain(SubscriptionDraftEntity entity) {
        if (entity == null) {
            return null;
        }
        Product product = new Product(entity.productCode, entity.productName,
                entity.productProfile, entity.annualRate);
        Subscriber subscriber = entity.firstName == null && entity.lastName == null
                && entity.email == null && entity.birthDate == null
                ? null
                : new Subscriber(entity.firstName, entity.lastName, entity.email,
                        entity.birthDate);
        Simulation simulation = entity.finalCapital == null
                ? null
                : new Simulation(entity.initialAmount, entity.monthlyAmount,
                        entity.durationYears == null ? 0 : entity.durationYears,
                        entity.contributed, entity.finalCapital);
        return new Draft(entity.draftId, entity.confirmationKey, product, subscriber, simulation,
                entity.expiresAt);
    }

    /** Reporte l'etat du domaine sur une entite geree ; {@code version} reste a Hibernate. */
    public static void apply(Draft draft, SubscriptionDraftEntity entity, Instant updatedAt) {
        entity.draftId = draft.draftId();
        entity.confirmationKey = draft.confirmationKey();
        entity.productCode = draft.product().code();
        entity.productName = draft.product().name();
        entity.productProfile = draft.product().profile();
        entity.annualRate = draft.product().annualRate();
        Subscriber subscriber = draft.subscriber();
        entity.firstName = subscriber == null ? null : subscriber.firstName();
        entity.lastName = subscriber == null ? null : subscriber.lastName();
        entity.email = subscriber == null ? null : subscriber.email();
        entity.birthDate = subscriber == null ? null : subscriber.birthDate();
        Simulation simulation = draft.simulation();
        entity.initialAmount = simulation == null ? null : simulation.initialAmount();
        entity.monthlyAmount = simulation == null ? null : simulation.monthlyAmount();
        entity.durationYears = simulation == null ? null : simulation.durationYears();
        entity.contributed = simulation == null ? null : simulation.contributed();
        entity.finalCapital = simulation == null ? null : simulation.finalCapital();
        entity.expiresAt = draft.expiresAt();
        entity.updatedAt = updatedAt;
        if (entity.createdAt == null) {
            entity.createdAt = updatedAt;
        }
    }

    /**
     * DraftView.json declare {@code additionalProperties: false} : la description du produit est
     * exigee par ProductDetail mais n'est pas persistee, l'appelant la fournit depuis le
     * catalogue.
     */
    public static DraftView toDto(Draft draft, String productDescription) {
        if (draft == null) {
            return null;
        }
        return new DraftView(draft.draftId(),
                ModelMapper.toDto(draft.product(), productDescription),
                ModelMapper.toDto(draft.subscriber()),
                ModelMapper.toDto(draft.simulation()),
                draft.confirmationKey(),
                draft.status().name());
    }
}
