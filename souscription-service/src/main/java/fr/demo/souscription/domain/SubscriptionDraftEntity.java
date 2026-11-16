package fr.demo.souscription.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Mapping JPA des 19 colonnes de db/migration/V2__subscription_draft.sql (W2-T01).
 *
 * <p>La classe et ses champs ne sont pas {@code final} : Hibernate doit pouvoir proxyfier
 * l'entite et le verrouillage optimiste {@code @Version} doit rester operant (ecart AG-002).
 *
 * <p>Les colonnes souscripteur et simulation sont nullables : elles reproduisent les etats
 * intermediaires du parcours source ({@code draft.getSubscriber() == null}).
 */
@Entity
@Table(name = "subscription_draft", schema = "subscriptions")
public class SubscriptionDraftEntity {

    @Id
    @Column(name = "draft_id", nullable = false, updatable = false)
    public UUID draftId;

    @Column(name = "confirmation_key", nullable = false)
    public UUID confirmationKey;

    @Column(name = "product_code", length = 24, nullable = false)
    public String productCode;

    @Column(name = "product_name", length = 80, nullable = false)
    public String productName;

    @Column(name = "product_profile", length = 40, nullable = false)
    public String productProfile;

    @Column(name = "annual_rate", precision = 8, scale = 6, nullable = false)
    public BigDecimal annualRate;

    @Column(name = "first_name", length = 80)
    public String firstName;

    @Column(name = "last_name", length = 80)
    public String lastName;

    @Column(name = "email", length = 254)
    public String email;

    @Column(name = "birth_date")
    public LocalDate birthDate;

    @Column(name = "initial_amount", precision = 14, scale = 2)
    public BigDecimal initialAmount;

    @Column(name = "monthly_amount", precision = 14, scale = 2)
    public BigDecimal monthlyAmount;

    @Column(name = "duration_years")
    public Integer durationYears;

    @Column(name = "contributed", precision = 14, scale = 2)
    public BigDecimal contributed;

    @Column(name = "final_capital", precision = 14, scale = 2)
    public BigDecimal finalCapital;

    @Version
    @Column(name = "version", nullable = false)
    public int version;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    /**
     * Cree un brouillon au statut {@code STARTED} : le produit est copie, le souscripteur et la
     * simulation restent nuls, et la cle de confirmation est tiree des maintenant comme le
     * faisait le constructeur du {@code model/Draft} source.
     */
    public static SubscriptionDraftEntity create(Product product, Instant now, Duration retention) {
        SubscriptionDraftEntity entity = new SubscriptionDraftEntity();
        entity.draftId = UUID.randomUUID();
        entity.confirmationKey = UUID.randomUUID();
        entity.productCode = product.code();
        entity.productName = product.name();
        entity.productProfile = product.profile();
        entity.annualRate = product.annualRate();
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.expiresAt = now.plus(retention);
        return entity;
    }

    /** Equivalent du {@code <session-timeout>20</session-timeout>} source. */
    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }
}
