package fr.demo.souscription.application;

import fr.demo.souscription.api.dto.DraftView;
import fr.demo.souscription.api.mapper.DraftMapper;
import fr.demo.souscription.domain.Product;
import fr.demo.souscription.domain.Simulation;
import fr.demo.souscription.domain.Subscriber;
import fr.demo.souscription.domain.SubscriptionDraftEntity;
import fr.demo.souscription.observability.BusinessMetrics;
import fr.demo.souscription.persistence.DraftRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.StaleStateException;
import org.jboss.logging.Logger;

/**
 * Etat du parcours de souscription, deplace de la session HTTP vers la base (W2-T03, W2-T04).
 *
 * <p>Les quatre attributs de session de {@code action/SubscriptionAction} sont portes ainsi :
 * <ul>
 *   <li>{@code draft} : une ligne de {@code subscriptions.subscription_draft}, clef
 *       {@code draft_id} ;</li>
 *   <li>{@code completed} : l'existence d'une ligne dans {@code subscriptions.subscriptions} ;</li>
 *   <li>la reference de la souscription achevee : {@code subscriptions.subscriptions.reference} ;</li>
 *   <li>la cle de la souscription achevee :
 *       {@code subscriptions.subscriptions.confirmation_key}.</li>
 * </ul>
 * Aucune session HTTP de servlet ni attribut de session ne subsiste dans ce module.
 *
 * <p>Le bloc de synchronisation sur la session source disparait : le controle de
 * concurrence est le verrou optimiste {@code @Version} de {@code SubscriptionDraftEntity} ;
 * la mise a jour perdante est rendue 409 / {@code DRAFT_CONCURRENT_UPDATE} (ecarts AG-016,
 * CFG-012).
 *
 * <p>Les transitions du parcours (souscripteur avant simulation, simulation avant recapitulatif)
 * restent ici et non dans une ressource REST. Toute mutation du brouillon regenere
 * {@code confirmation_key} dans la meme transaction, ce qui invalide une confirmation obsolete.
 */
@ApplicationScoped
public class DraftService {

    private static final Logger LOG = Logger.getLogger(DraftService.class);

    /**
     * Retention du brouillon, equivalent explicite du {@code <session-timeout>20</session-timeout>}
     * source (W2-T05). Valeur par defaut {@code PT20M}, portee par
     * {@code application.properties}.
     */
    @ConfigProperty(name = "horizon.draft.retention")
    Duration retention;

    @Inject
    DraftRepository drafts;

    @Inject
    Clock clock;

    /** Compteur metier des brouillons ouverts (W5-T03). */
    @Inject
    BusinessMetrics metrics;

    /**
     * Auto-injection : la frontiere {@code @Transactional} est posee par interception CDI, qui
     * ne s'applique pas a un appel direct {@code this.methode()}. Le conflit du verrou optimiste
     * est leve au commit, donc a la sortie de la methode interceptee : il ne peut etre rattrape
     * que depuis un appelant situe hors de la transaction, d'ou le passage par le proxy.
     */
    @Inject
    DraftService self;

    @Transactional
    public DraftView start(String productCode, Product product) {
        SubscriptionDraftEntity entity =
                SubscriptionDraftEntity.create(product, clock.instant(), retention);
        drafts.persist(entity);
        metrics.draftsCreated().increment();
        LOG.debugf("Brouillon cree ; draftId=%s productCode=%s", entity.draftId, productCode);
        return view(entity);
    }

    /**
     * Un brouillon dont {@code expires_at} est passe est traite comme absent : la ressource REST
     * repond <b>404 Not Found</b>, aussi bien pour un brouillon expire que pour un identifiant
     * inconnu. Le contrat gele
     * {@code global/contracts/souscription-gateway-to-souscription-service/openapi.yaml} ne declare
     * que {@code 200 / 404 / 500} sur {@code GET /drafts/{draftId}} : il n'y a pas de reponse
     * {@code Gone}. Le 404 est l'equivalent cible de la reprise source, qui redirigeait
     * vers {@code /catalogue.do?notice=parcours}.
     */
    public Optional<DraftView> read(UUID draftId) {
        return drafts.findByIdOptional(draftId)
                .filter(draft -> !draft.isExpired(clock.instant()))
                .map(DraftService::view);
    }

    /**
     * Mise a jour du souscripteur. Deux ecritures concurrentes sur le meme brouillon sont
     * arbitrees par le verrou optimiste {@code @Version} : la perdante devient un
     * {@link DraftStateException} de code {@link DraftStateException#DRAFT_CONCURRENT_UPDATE},
     * rendu <b>409 Conflict</b>.
     */
    public DraftView updateSubscriber(UUID draftId, Subscriber subscriber) {
        return concurrencySafe(draftId, () -> self.applySubscriber(draftId, subscriber));
    }

    @Transactional
    DraftView applySubscriber(UUID draftId, Subscriber subscriber) {
        SubscriptionDraftEntity entity = active(draftId);
        entity.firstName = subscriber == null ? null : subscriber.firstName();
        entity.lastName = subscriber == null ? null : subscriber.lastName();
        entity.email = subscriber == null ? null : subscriber.email();
        entity.birthDate = subscriber == null ? null : subscriber.birthDate();
        entity.confirmationKey = UUID.randomUUID();
        entity.updatedAt = clock.instant();
        LOG.debugf("Souscripteur enregistre, cle de confirmation regeneree ; draftId=%s", draftId);
        return view(entity);
    }

    /** Meme arbitrage de concurrence que {@link #updateSubscriber(UUID, Subscriber)}. */
    public DraftView updateSimulation(UUID draftId, Simulation simulation) {
        return concurrencySafe(draftId, () -> self.applySimulation(draftId, simulation));
    }

    @Transactional
    DraftView applySimulation(UUID draftId, Simulation simulation) {
        SubscriptionDraftEntity entity = active(draftId);
        if (entity.firstName == null && entity.lastName == null && entity.email == null
                && entity.birthDate == null) {
            throw new IllegalStateException(
                    "le souscripteur doit etre saisi avant la simulation : draftId=" + draftId);
        }
        entity.initialAmount = simulation == null ? null : simulation.initialAmount();
        entity.monthlyAmount = simulation == null ? null : simulation.monthlyAmount();
        entity.durationYears = simulation == null ? null : simulation.durationYears();
        entity.contributed = simulation == null ? null : simulation.contributed();
        entity.finalCapital = simulation == null ? null : simulation.finalCapital();
        entity.confirmationKey = UUID.randomUUID();
        entity.updatedAt = clock.instant();
        LOG.debugf("Simulation enregistree, cle de confirmation regeneree ; draftId=%s", draftId);
        return view(entity);
    }

    /** Le recapitulatif n'est accessible qu'apres les etapes 2 et 3 du parcours. */
    public boolean isReadyForSummary(DraftView draft) {
        return draft.subscriber() != null && draft.simulation() != null;
    }

    /**
     * Arbitrage des ecritures concurrentes sur un meme brouillon, en remplacement du
     * bloc de synchronisation sur la session de la source (ecarts AG-016, CFG-012). Le moniteur
     * serialisait toutes les requetes, lectures comprises ; le verrou optimiste ne protege que
     * les ecritures, les lectures de {@code GET /v1/drafts/{id}} restent concurrentes et voient
     * un etat valide sous l'isolation {@code READ COMMITTED} de PostgreSQL, qui n'est pas
     * relevee.
     */
    private DraftView concurrencySafe(UUID draftId, Supplier<DraftView> update) {
        try {
            return update.get();
        } catch (RuntimeException failure) {
            if (!isOptimisticLock(failure)) {
                throw failure;
            }
            LOG.debugf("Mise a jour concurrente du brouillon rejetee ; draftId=%s", draftId);
            throw new DraftStateException("draft", DraftStateException.DRAFT_CONCURRENT_UPDATE,
                    "le brouillon a ete modifie par une autre requete : draftId=" + draftId);
        }
    }

    private static boolean isOptimisticLock(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof OptimisticLockException
                    || current instanceof StaleStateException) {
                return true;
            }
            if (current == current.getCause()) {
                return false;
            }
        }
        return false;
    }

    private SubscriptionDraftEntity active(UUID draftId) {
        Instant now = clock.instant();
        SubscriptionDraftEntity entity = drafts.findByIdOptional(draftId)
                .orElseThrow(() -> new NoSuchElementException("brouillon inconnu : " + draftId));
        if (entity.isExpired(now)) {
            throw new NoSuchElementException("brouillon expire : " + draftId);
        }
        return entity;
    }

    /**
     * La description du produit est detenue par le catalogue et n'est pas persistee dans le
     * brouillon : elle est rattachee par la couche REST, pas ici.
     */
    private static DraftView view(SubscriptionDraftEntity entity) {
        return DraftMapper.toDto(DraftMapper.toDomain(entity), null);
    }
}
