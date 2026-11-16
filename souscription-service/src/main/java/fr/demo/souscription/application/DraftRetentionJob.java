package fr.demo.souscription.application;

import fr.demo.souscription.persistence.DraftRepository;
import fr.demo.souscription.observability.BusinessMetrics;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * Purge planifiee des brouillons expires (W2-T05).
 *
 * <p>Le {@code <session-timeout>20</session-timeout>} source deleguait l'expiration au conteneur
 * de servlets, qui liberait la memoire de la session. Ici la retention est explicite
 * ({@code horizon.draft.retention}, defaut {@code PT20M}, portee par {@code expires_at}) et la
 * liberation est une suppression de lignes planifiee, metree par le compteur
 * {@code horizon.draft.purged}.
 *
 * <p>Les regles d'alerte associees (compteur non nul sur une heure de trafic, nombre de lignes de
 * {@code subscription_draft} stable) sont declarees dans {@code deploy/DRAFT_ALERTS.md}
 * (mitigation R-002).
 */
@ApplicationScoped
public class DraftRetentionJob {

    private static final Logger LOG = Logger.getLogger(DraftRetentionJob.class);

    @Inject
    DraftRepository drafts;

    @Inject
    Clock clock;

    @Inject
    BusinessMetrics metrics;

    @Scheduled(cron = "{horizon.draft.purge.cron}")
    @Transactional
    void purgeExpiredDrafts() {
        long deleted = drafts.delete("expiresAt < ?1", Instant.now(clock));
        metrics.purged().increment(deleted);
        LOG.debugf("Purge des brouillons expires ; deleted=%d", deleted);
    }
}
