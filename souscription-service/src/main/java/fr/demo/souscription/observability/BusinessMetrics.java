package fr.demo.souscription.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Compteurs metier exposes sur {@code /q/metrics} (W5-T03).
 *
 * <p>La source n'exposait aucune metrique : ni technique, ni metier. Les cinq compteurs ci-dessous
 * sont la seule declaration de leurs noms dans le module ; aucun appelant ne doit appeler
 * {@code registry.counter("horizon...")} directement, sous peine d'enregistrer un second meter
 * sous un nom voisin et de rendre les tableaux de bord faux sans aucun echec de build.
 *
 * <ul>
 *   <li>{@value #DRAFTS_CREATED} : brouillons ouverts ({@code DraftService#start});</li>
 *   <li>{@value #CONFIRMATIONS} : confirmations effectivement enregistrees ;</li>
 *   <li>{@value #DUPLICATES} : rejeux court-circuites par la cle d'idempotence, etiquetes par
 *       chemin ({@code read} = cle deja confirmee, {@code conflict} = course arbitree par la
 *       contrainte unique) ;</li>
 *   <li>{@value #VALIDATION_REJECTED} : requetes refusees par la validation, incrementees
 *       exclusivement par {@code ConstraintViolationMapper} pour que chaque rejet soit compte
 *       une fois et une seule, quelle que soit la ressource ;</li>
 *   <li>{@value #DRAFT_PURGED} : lignes de brouillon supprimees par la purge planifiee (W2-T05).</li>
 * </ul>
 *
 * <p>Noms Prometheus apres conversion du registre : les points deviennent des underscores et un
 * suffixe {@code _total} est ajoute ({@code horizon_drafts_created_total}, ...).
 */
@Startup
@ApplicationScoped
public class BusinessMetrics {

    public static final String DRAFTS_CREATED = "horizon.drafts.created";
    public static final String CONFIRMATIONS = "horizon.confirmations.succeeded";
    public static final String DUPLICATES = "horizon.confirmations.duplicate";
    public static final String VALIDATION_REJECTED = "horizon.validation.rejected";
    public static final String DRAFT_PURGED = "horizon.draft.purged";

    /**
     * Le registre est resolu paresseusement : avec {@code quarkus.micrometer.enabled=false}
     * (chemin de retour arriere documente dans {@code deploy/OBSERVABILITY.md}), aucun
     * {@code MeterRegistry} n'est produit. Une injection directe rendrait l'instrumentation
     * porteuse du demarrage : l'application ne demarrerait plus du tout. Le repli est un registre
     * local, non expose, qui laisse les sites d'increment inchanges.
     */
    @Inject
    Instance<MeterRegistry> registryInstance;

    private MeterRegistry registry;

    /**
     * Enregistrement anticipe au demarrage ({@code @Startup}) : un compteur Micrometer n'existe
     * dans le registre qu'a sa premiere incrementation. Sans cette pre-declaration, une serie
     * absente de {@code /q/metrics} serait indistinguable d'un code jamais atteint, les requetes
     * PromQL des tableaux de bord renverraient « no data » au lieu de zero, et une regle
     * d'alerte sur l'absence de purge ne se declencherait jamais.
     */
    @PostConstruct
    void preRegister() {
        registry = registryInstance.isResolvable()
                ? registryInstance.get()
                : new SimpleMeterRegistry();
        draftsCreated();
        confirmations();
        duplicates("read");
        duplicates("conflict");
        validationFails();
        purged();
    }

    public Counter draftsCreated() {
        return registry.counter(DRAFTS_CREATED);
    }

    public Counter confirmations() {
        return registry.counter(CONFIRMATIONS);
    }

    /**
     * @param path {@code read} ou {@code conflict} : les deux chemins idempotents partagent le
     *             meme meter, seule l'etiquette les distingue.
     */
    public Counter duplicates(String path) {
        return registry.counter(DUPLICATES, "path", path);
    }

    public Counter validationFails() {
        return registry.counter(VALIDATION_REJECTED);
    }

    public Counter purged() {
        return registry.counter(DRAFT_PURGED);
    }
}
