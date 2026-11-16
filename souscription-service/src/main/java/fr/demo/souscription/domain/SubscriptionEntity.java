package fr.demo.souscription.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Mapping JPA des 16 colonnes de db/migration/V1__subscriptions.sql (W1-T04).
 *
 * <p>La classe et ses champs ne sont pas {@code final} : Hibernate et CDI doivent pouvoir
 * proxyfier l'entite (ecart AG-002). Les echelles decimales sont celles du DDL : les montants
 * restent en numeric(14,2) et le taux en numeric(8,6), jamais en double.
 */
@Entity
@Table(name = "subscriptions", schema = "subscriptions")
public class SubscriptionEntity {

    @Id
    @Column(name = "reference", nullable = false, updatable = false)
    public UUID reference;

    @Column(name = "confirmation_key", unique = true, nullable = false)
    public UUID confirmationKey;

    @Column(name = "product_code", length = 24, nullable = false)
    public String productCode;

    @Column(name = "product_name", length = 80, nullable = false)
    public String productName;

    @Column(name = "product_profile", length = 40, nullable = false)
    public String productProfile;

    @Column(name = "annual_rate", precision = 8, scale = 6, nullable = false)
    public BigDecimal annualRate;

    @Column(name = "first_name", length = 80, nullable = false)
    public String firstName;

    @Column(name = "last_name", length = 80, nullable = false)
    public String lastName;

    @Column(name = "email", length = 254, nullable = false)
    public String email;

    @Column(name = "birth_date", nullable = false)
    public LocalDate birthDate;

    @Column(name = "initial_amount", precision = 14, scale = 2, nullable = false)
    public BigDecimal initialAmount;

    @Column(name = "monthly_amount", precision = 14, scale = 2, nullable = false)
    public BigDecimal monthlyAmount;

    @Column(name = "duration_years", nullable = false)
    public int durationYears;

    @Column(name = "contributed", precision = 14, scale = 2, nullable = false)
    public BigDecimal contributed;

    @Column(name = "final_capital", precision = 14, scale = 2, nullable = false)
    public BigDecimal finalCapital;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
