package fr.demo.souscription.persistence;

import fr.demo.souscription.domain.SubscriptionEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;

/**
 * Remplace le DAO JDBC ecrit a la main (W1-T04).
 *
 * <p>{@code PanacheRepositoryBase} et non {@code PanacheEntity} : la cle primaire
 * {@code reference} est un uuid fourni par l'application, pas une sequence generee.
 *
 * <p>{@code persist} propage telle quelle l'exception de violation de contrainte unique sur
 * {@code confirmation_key} : c'est {@code ConfirmationService} qui l'interprete par
 * {@code SQLState}, a l'interieur de sa frontiere {@code @Transactional}, pour servir la
 * confirmation idempotente (ecarts FG-015, CFG-004, AG-012).
 */
@ApplicationScoped
public class SubscriptionRepository implements PanacheRepositoryBase<SubscriptionEntity, UUID> {

    public Optional<SubscriptionEntity> findByConfirmationKey(UUID key) {
        return find("confirmationKey", key).firstResultOptional();
    }

    /** Contrat de lecture public du DAO source, conserve pour la compatibilite d'API. */
    public Optional<SubscriptionEntity> findByKey(UUID key) {
        return findByConfirmationKey(key);
    }
}
