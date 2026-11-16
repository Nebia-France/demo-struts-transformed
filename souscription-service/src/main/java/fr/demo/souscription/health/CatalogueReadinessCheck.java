package fr.demo.souscription.health;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness de la dependance catalogue (W6-T06) : {@code souscription-catalogue-service} est la
 * seule source du taux annuel, aucune valeur de repli n'est servie. Une indisponibilite durable du
 * catalogue rend {@code startDraft} inexploitable, donc l'instance inapte.
 *
 * <p>{@code DOWN} seulement lorsque le disjoncteur de {@link
 * fr.demo.souscription.integration.CatalogueClient#findProduct} est ouvert de maniere
 * <b>continue</b> depuis au moins {@link CircuitBreakerOpenWindow#DURABLY_OPEN} (30 s). Un echec
 * isole, un timeout unique ou une ouverture suivie d'un retablissement laissent la sonde
 * {@code UP} : retirer l'instance de la rotation sur une faute transitoire la retire sans qu'aucune
 * autre instance ne soit en meilleur etat, et interrompt aussi les operations de lecture qui
 * n'appellent pas le catalogue.
 */
@Readiness
@ApplicationScoped
public class CatalogueReadinessCheck implements HealthCheck {

    static final String CHECK_NAME = "catalogue";

    private final CircuitBreakerOpenWindow window =
            new CircuitBreakerOpenWindow("CatalogueClient");

    /**
     * Injecte en {@code Instance} : le registre Micrometer est absent lorsque
     * {@code quarkus.micrometer.enabled=false}, et une sonde de sante ne doit pas empecher le
     * demarrage dans cette configuration.
     */
    @Inject
    Instance<MeterRegistry> registry;

    @Override
    public HealthCheckResponse call() {
        return DependencyReadinessCheck.evaluate(CHECK_NAME, window, registry);
    }
}
