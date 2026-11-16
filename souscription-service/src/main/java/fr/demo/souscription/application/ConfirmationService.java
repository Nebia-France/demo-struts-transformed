package fr.demo.souscription.application;

import fr.demo.souscription.api.dto.SimulationInput;
import fr.demo.souscription.api.dto.SimulationResult;
import fr.demo.souscription.api.dto.SubscriberInput;
import fr.demo.souscription.api.dto.SubscriptionConfirmation;
import fr.demo.souscription.api.mapper.ModelMapper;
import fr.demo.souscription.api.mapper.SubscriptionMapper;
import fr.demo.souscription.domain.Product;
import fr.demo.souscription.domain.Subscriber;
import fr.demo.souscription.domain.Subscription;
import fr.demo.souscription.domain.SubscriptionDraftEntity;
import fr.demo.souscription.domain.SubscriptionEntity;
import fr.demo.souscription.integration.CatalogueLookup;
import fr.demo.souscription.integration.SimulationLookup;
import fr.demo.souscription.integration.SimulationRejectedException;
import fr.demo.souscription.integration.SimulationComputeRequest;
import fr.demo.souscription.persistence.DraftRepository;
import fr.demo.souscription.persistence.SubscriptionRepository;
import fr.demo.souscription.observability.BusinessMetrics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Confirmation de la souscription (W3-T02, W3-T06, W4-T05).
 *
 * <p>Remplace le graphe d'objets cable a la main au demarrage du contexte web source : les
 * collaborateurs ne sont plus construits par un ecouteur de contexte de servlet ni retrouves par
 * un service-locator, ils sont injectes par constructeur et resolus par ArC.
 *
 * <p>La simulation reste un appel distant au moment de la confirmation (decision de topologie
 * confirmee), via {@link SimulationLookup}.
 *
 * <p><b>Ordre impose (NFG-009) :</b> l'appel distant de simulation est effectue <b>avant</b>
 * l'entree dans toute methode {@code @Transactional}. Aucune connexion de base n'est retenue
 * pendant un appel reseau ; les plans ulterieurs qui ajoutent des appels sortants
 * (resilience, clients distants) doivent conserver cet ordre : valeurs distantes d'abord,
 * transaction ensuite.
 *
 * <p>L'idempotence de la source — lecture, insertion, relecture sur violation d'unicite — est
 * conservee telle quelle : {@link #confirmIdempotent(UUID, UUID)} lit la cle, tente l'insertion
 * dans une transaction geree par conteneur, et relit dans une transaction neuve si la contrainte
 * {@code UNIQUE (confirmation_key)} a tranche entre deux requetes concurrentes. Il n'y a plus
 * aucun pilotage manuel du mode de validation JDBC ni des bornes de transaction.
 *
 * <p>Le contrat implicite de la source (null-check de session, redirections de reprise,
 * erreurs brutes de servlet) devient un contrat de statut explicite :
 * <ul>
 *   <li>brouillon absent, expire ou identifiant inconnu : <b>404 Not Found</b> ; le contrat gele
 *       ne declare pas de 410 Gone ;</li>
 *   <li>cle de confirmation divergente : <b>409 Conflict</b> ;</li>
 *   <li>parcours incomplet (souscripteur ou simulation manquant) : <b>409 Conflict</b>, code
 *       {@code PRECONDITION_SEQUENCE} ;</li>
 *   <li>corps invalide (cle absente ou non-uuid) : <b>400 Bad Request</b> ;</li>
 *   <li>stockage indisponible : <b>503 Service Unavailable</b> ;</li>
 *   <li>reference de souscription inconnue : <b>404 Not Found</b>.</li>
 * </ul>
 */
@ApplicationScoped
public class ConfirmationService {

    private static final Logger LOG = Logger.getLogger(ConfirmationService.class);

    /** Code d'erreur du parcours incomplet, porte par le corps {@code problem+json} (plan 22). */
    public static final String PRECONDITION_SEQUENCE = "PRECONDITION_SEQUENCE";

    /**
     * Produit retire du catalogue entre le debut du parcours et la confirmation : conflit d'etat
     * (409) et non un 5xx, le catalogue ayant repondu normalement.
     */
    public static final String PRODUCT_WITHDRAWN = "PRODUCT_WITHDRAWN";

    /** {@code SQLState} de la violation de contrainte unique, identique sur PostgreSQL et H2. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final SubscriptionRepository repository;
    private final DraftRepository drafts;
    private final SimulationLookup simulations;
    private final CatalogueLookup catalogue;
    private final Clock clock;

    /**
     * Auto-injection : les frontieres {@code @Transactional} de ce service sont posees par
     * interception CDI, qui ne s'applique pas a un appel direct {@code this.methode()}. Les
     * methodes transactionnelles sont donc invoquees au travers du proxy.
     */
    @Inject
    ConfirmationService self;

    /**
     * Compteurs metier (W5-T03). Les noms sont detenus par {@link BusinessMetrics} : le compteur
     * de rejeu est le meme meter que celui des tableaux de bord, il n'est jamais re-declare ici.
     */
    @Inject
    BusinessMetrics metrics;

    /**
     * Revalidation serveur a la confirmation (W7-T06). Le client n'est <b>jamais</b> fait
     * confiance : les contraintes des DTO d'ecriture sont rejouees sur le contenu du brouillon,
     * independamment de ce que la SPA ou la couche REST ont pu valider plus tot. Un brouillon
     * rendu invalide par un autre chemin (ecriture directe en base, client modifie, regle
     * durcie entre la saisie et la confirmation) est refuse ici.
     */
    @Inject
    Validator validator;

    @Inject
    public ConfirmationService(SubscriptionRepository repository, DraftRepository drafts,
                               SimulationLookup simulations, CatalogueLookup catalogue,
                               Clock clock) {
        this.repository = repository;
        this.drafts = drafts;
        this.simulations = simulations;
        this.catalogue = catalogue;
        this.clock = clock;
    }

    /**
     * Resultat d'une confirmation : {@code created} distingue la premiere confirmation (201) du
     * rejeu de la meme cle (200), qui renvoie la meme reference.
     */
    public record Outcome(boolean created, SubscriptionConfirmation confirmation) {
    }

    /**
     * Confirme un brouillon complet. Rejouer la meme {@code confirmationKey} renvoie la meme
     * {@code reference} avec le statut 200 et ne cree jamais une seconde ligne : l'unicite de
     * {@code confirmation_key}, deja presente dans la source, porte cette idempotence.
     *
     * <p>Contrat d'API conserve : delegue a {@link #confirmIdempotent(UUID, UUID)}.
     */
    public Outcome confirm(UUID draftId, UUID confirmationKey) {
        return confirmIdempotent(draftId, confirmationKey);
    }

    /**
     * Confirmation idempotente sur {@code confirmationKey}.
     *
     * <p>Trois chemins, tous sans seconde ligne possible :
     * <ol>
     *   <li>la cle est deja confirmee : relecture immediate, {@code created = false} ;</li>
     *   <li>l'insertion reussit : {@code created = true} ;</li>
     *   <li>une confirmation concurrente a gagne la course : la contrainte unique est levee
     *       dans la transaction, qui est annulee, et la relecture se fait dans une transaction
     *       neuve ({@code REQUIRES_NEW}), {@code created = false}.</li>
     * </ol>
     *
     * <p>L'appel distant de simulation precede l'ouverture de la transaction (NFG-009).
     */
    public Outcome confirmIdempotent(UUID draftId, UUID confirmationKey) {
        Optional<SubscriptionEntity> replayed = repository.findByConfirmationKey(confirmationKey);
        if (replayed.isPresent()) {
            metrics.duplicates("read").increment();
            LOG.debugf("Rejeu de confirmation ; draftId=%s reference=%s", draftId,
                    replayed.get().reference);
            return new Outcome(false, SubscriptionMapper.toConfirmation(replayed.get()));
        }

        Subscription subscription = prepare(draftId, confirmationKey);
        try {
            SubscriptionConfirmation persisted = self.write(subscription);
            metrics.confirmations().increment();
            LOG.debugf("Souscription confirmee ; draftId=%s reference=%s", draftId,
                    persisted.reference());
            return new Outcome(true, persisted);
        } catch (DuplicateConfirmationException conflict) {
            metrics.duplicates("conflict").increment();
            LOG.debugf("Confirmation concurrente sur la meme cle ; draftId=%s", draftId);
            return new Outcome(false, self.rereadAfterConflict(confirmationKey));
        }
    }

    /**
     * Lecture servie uniquement par la ligne denormalisee : aucun appel au catalogue ni a la
     * simulation. Une reference inconnue est un 404, laisse a l'appelant.
     */
    public Optional<SubscriptionConfirmation> findByReference(String reference) {
        UUID key;
        try {
            key = UUID.fromString(reference);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        return repository.findByIdOptional(key).map(SubscriptionMapper::toConfirmation);
    }

    /**
     * Controles du parcours et appel distant de simulation, hors transaction : l'etat a inserer
     * est entierement constitue avant que la moindre connexion ne soit retenue.
     *
     * <p>Le taux annuel est <b>relu au catalogue</b> a la confirmation : le contrat consomme
     * declare un appel par confirmation et designe le catalogue comme seule source autoritaire du
     * taux (cf. {@code resources/INTEGRATION_DECISIONS.md}). Le taux copie dans le brouillon a la
     * creation sert l'affichage ; la valeur persistee est celle du catalogue au moment de la
     * confirmation. L'appel precede l'ouverture de la transaction (NFG-009).
     */
    private Subscription prepare(UUID draftId, UUID confirmationKey) {
        SubscriptionDraftEntity draft = drafts.findByIdOptional(draftId)
                .filter(entity -> !entity.isExpired(clock.instant()))
                .orElseThrow(() -> new NotFoundException("brouillon inconnu ou expire : " + draftId));

        if (!draft.confirmationKey.equals(confirmationKey)) {
            throw new DraftStateException("confirmationKey",
                    DraftStateException.CONFIRMATION_KEY_MISMATCH,
                    "la cle de confirmation ne correspond plus au brouillon : " + draftId);
        }
        boolean subscriberMissing = draft.firstName == null && draft.lastName == null
                && draft.email == null && draft.birthDate == null;
        if (subscriberMissing || draft.finalCapital == null) {
            throw new DraftStateException("confirmationKey", PRECONDITION_SEQUENCE,
                    PRECONDITION_SEQUENCE
                    + " : le souscripteur et la simulation doivent etre saisis avant la"
                    + " confirmation ; draftId=" + draftId);
        }

        revalidate(draft);

        Product product = ModelMapper.toDomain(catalogue.findProduct(draft.productCode)
                .orElseThrow(() -> new DraftStateException("productCode", PRODUCT_WITHDRAWN,
                        PRODUCT_WITHDRAWN + " : le produit n'est plus au catalogue ; draftId="
                        + draftId)));

        SimulationResult computed;
        try {
            computed = simulations.compute(new SimulationComputeRequest(
                    ModelMapper.amount(draft.initialAmount), ModelMapper.amount(draft.monthlyAmount),
                    draft.durationYears, ModelMapper.rate(product.annualRate())));
        } catch (SimulationRejectedException refused) {
            // Verdict du fournisseur : relaye tel quel en 400, avec ses erreurs par champ.
            throw refused;
        } catch (IllegalArgumentException invalid) {
            throw new ClientErrorException(invalid.getMessage(), Response.Status.BAD_REQUEST,
                    invalid);
        }

        return new Subscription(UUID.randomUUID(), confirmationKey, product,
                new Subscriber(draft.firstName, draft.lastName, draft.email, draft.birthDate),
                ModelMapper.toDomain(computed), clock.instant());
    }

    /**
     * Rejoue les contraintes de {@link SubscriberInput} et {@link SimulationInput} sur le
     * contenu du brouillon avant toute persistance (W7-T06, NFG-006).
     *
     * <p>La validation de la couche REST ne couvre que la requete qui l'a traversee : rien ne
     * garantit que le brouillon lu en base a ete ecrit par ce chemin. Les memes contraintes sont
     * donc re-executees ici au travers du {@link Validator} injecte, et un verdict negatif est
     * leve en {@link ConstraintViolationException} — exactement l'exception que
     * {@code api/problem/ConstraintViolationMapper} rend en <b>400</b>
     * {@code application/problem+json}. Le corps d'erreur est donc identique, cle de message et
     * code stable compris, qu'une regle soit violee a la saisie ou a la confirmation.
     */
    private void revalidate(SubscriptionDraftEntity draft) {
        Set<ConstraintViolation<?>> violations = new LinkedHashSet<>(
                validator.validate(new SubscriberInput(draft.firstName, draft.lastName,
                        draft.email, text(draft.birthDate))));
        violations.addAll(validator.validate(new SimulationInput(amountText(draft.initialAmount),
                amountText(draft.monthlyAmount), draft.durationYears)));
        if (!violations.isEmpty()) {
            LOG.debugf("Brouillon invalide a la confirmation ; draftId=%s champs=%s",
                    draft.draftId, violations.size());
            throw new ConstraintViolationException(violations);
        }
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    /**
     * Rend le montant sous la forme du fil ({@code ^[0-9]{1,6}\.[0-9]{2}$}) pour que la
     * contrainte de motif portee par le DTO s'applique a l'identique. Un montant absent reste
     * nul : c'est la contrainte {@code @NotNull} du DTO qui tranche, jamais un defaut implicite.
     * Une echelle superieure a deux decimales n'est pas arrondie : elle est rendue telle quelle
     * et refusee par le motif, l'arrondi silencieux etant precisement le fail-open a eviter.
     */
    private static String amountText(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.scale() <= 2
                ? amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                : amount.toPlainString();
    }

    /**
     * Ecriture de la souscription dans une transaction geree par conteneur.
     *
     * <p>Le {@code flush()} explicite est indispensable : sans lui, Hibernate ne detecterait la
     * violation d'unicite qu'au commit, hors de ce bloc {@code try}, et le conflit ne pourrait
     * plus etre distingue d'une panne de stockage.
     *
     * <p>Un echec de stockage est traduit en {@link StorageUnavailableException}, rendu
     * <b>503 Service Unavailable</b> et non en 500 : la semantique de la source est conservee
     * telle quelle (ecart FG-003) — aucune confirmation n'a ete emise, la saisie de l'utilisateur
     * est conservee dans le brouillon et la requete peut etre rejouee a l'identique. Un 500
     * changerait le comportement de reprise du SPA. Le {@code SQLState} est journalise par le
     * mapper et n'est jamais renvoye au client.
     */
    @Transactional
    SubscriptionConfirmation write(Subscription subscription) {
        SubscriptionEntity entity = SubscriptionMapper.toEntity(subscription);
        try {
            repository.persist(entity);
            repository.flush();
        } catch (PersistenceException storageFailure) {
            if (isUniqueViolation(storageFailure)) {
                throw new DuplicateConfirmationException(subscription.confirmationKey(),
                        storageFailure);
            }
            throw new StorageUnavailableException("enregistrement refuse ; aucune confirmation"
                    + " emise, la saisie est conservee et la requete peut etre rejouee",
                    storageFailure);
        }
        return SubscriptionMapper.toConfirmation(entity);
    }

    /**
     * Relecture apres conflit d'unicite, dans une transaction <b>neuve</b> : la transaction
     * d'insertion est marquee rollback-only, aucune lecture n'y est plus possible.
     *
     * <p>Une absence de ligne apres un conflit est une incoherence de stockage, pas une erreur
     * de l'appelant : elle est rendue en 503, jamais en 500.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    SubscriptionConfirmation rereadAfterConflict(UUID confirmationKey) {
        return repository.findByConfirmationKey(confirmationKey)
                .map(SubscriptionMapper::toConfirmation)
                .orElseThrow(() -> new StorageUnavailableException(
                        "conflit d'unicite sans ligne correspondante ; la requete peut etre"
                        + " rejouee a l'identique", null));
    }

    /**
     * Detection de la violation d'unicite par {@code SQLState} et jamais par le texte du
     * message : {@value #UNIQUE_VIOLATION} est identique sur PostgreSQL et H2.
     */
    private static boolean isUniqueViolation(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
            if (current == current.getCause()) {
                return false;
            }
        }
        return false;
    }
}
