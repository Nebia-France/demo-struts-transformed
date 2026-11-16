package fr.demo.souscription.health;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness de la dependance simulation (W6-T06) : le recalcul autoritaire de la projection est un
 * appel distant emis sur le chemin de confirmation, et aucune projection de repli n'est jamais
 * persistee. Une indisponibilite durable de {@code souscription-simulation-service} rend donc
 * {@code confirmDraft} inexploitable.
 *
 * <p>Meme regle que {@link CatalogueReadinessCheck} : {@code DOWN} seulement sur une ouverture
 * <b>continue</b> du disjoncteur de {@link
 * fr.demo.souscription.integration.SimulationClient#compute} depuis au moins
 * {@link CircuitBreakerOpenWindow#DURABLY_OPEN} (30 s). Une faute transitoire reste un
 * <b>503 rejouable</b> rendu a l'appelant, pas un retrait de l'instance.
 */
@Readiness
@ApplicationScoped
public class SimulationReadinessCheck implements HealthCheck {

    static final String CHECK_NAME = "simulation";

    private final CircuitBreakerOpenWindow window =
            new CircuitBreakerOpenWindow("SimulationClient");

    @Inject
    Instance<MeterRegistry> registry;

    @Override
    public HealthCheckResponse call() {
        return DependencyReadinessCheck.evaluate(CHECK_NAME, window, registry);
    }
}
