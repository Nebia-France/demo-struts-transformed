package fr.demo.souscription.persistence;

import fr.demo.souscription.domain.SubscriptionDraftEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

/**
 * Acces au brouillon serveur, qui remplace le stockage en session HTTP de servlet (W2-T03).
 *
 * <p>{@code PanacheRepositoryBase} et non {@code PanacheEntity} : la cle primaire
 * {@code draft_id} est un UUIDv4 fourni par l'application, jamais une sequence generee
 * (jeton opaque non enumerable, mitigation R-002).
 */
@ApplicationScoped
public class DraftRepository implements PanacheRepositoryBase<SubscriptionDraftEntity, UUID> {

    /**
     * Effacement a la demande d'un brouillon, point d'entree du droit a l'effacement (W7-T07,
     * ecart CFG-011). La purge planifiee de {@code DraftRetentionJob} couvre le cas courant ;
     * cette methode couvre la demande nominative d'une personne concernee avant l'echeance de
     * retention.
     *
     * <p>Volontairement non expose en HTTP : l'API {@code /v1} est non authentifiee, un point
     * d'effacement ouvert serait une suppression arbitraire de brouillon par tiers. La procedure
     * operatoire est decrite dans {@code COMPLIANCE_RGPD.md}.
     *
     * @return le nombre de lignes supprimees, 0 si le brouillon est inconnu ou deja purge
     */
    public long deleteByDraftId(UUID draftId) {
        return delete("draftId", draftId);
    }
}
