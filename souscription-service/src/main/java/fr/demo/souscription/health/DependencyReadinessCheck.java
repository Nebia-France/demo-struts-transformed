package fr.demo.souscription.health;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import java.time.Duration;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Sonde de readiness des dependances sortantes (W5-T02, renseignee par W6-T06).
 *
 * <p>Le detail est porte par une sonde par dependance — {@link CatalogueReadinessCheck} et
 * {@link SimulationReadinessCheck} — pour qu'une readiness {@code DOWN} nomme la dependance
 * fautive au lieu d'agreger les deux sous un verdict unique. Cette sonde conserve le nom
 * {@code dependencies} qui existait des le plan 25 et reste le point d'entree declare par les
 * manifestes de deploiement : elle enumere les sondes par dependance et la regle qui les gouverne.
 *
 * <p>Elle porte la decision commune {@link #evaluate} : {@code DOWN} seulement lorsque le
 * disjoncteur d'une dependance est ouvert de maniere <b>durable</b>
 * ({@link CircuitBreakerOpenWindow#DURABLY_OPEN}). L'etat est lu dans les jauges Micrometer de
 * SmallRye Fault Tolerance, jamais re-suivi.
 */
@Readiness
@ApplicationScoped
public class DependencyReadinessCheck implements HealthCheck {

    static final String CHECK_NAME = "dependencies";

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.builder()
                .name(CHECK_NAME)
                .up()
                .withData("checks",
                        CatalogueReadinessCheck.CHECK_NAME + "," + SimulationReadinessCheck.CHECK_NAME)
                .withData("downAfter", CircuitBreakerOpenWindow.DURABLY_OPEN.toString())
                .build();
    }

    /**
     * Verdict commun aux deux sondes par dependance.
     *
     * <p>Sans registre Micrometer, l'etat du disjoncteur n'est pas observable : la sonde repond
     * {@code UP}. Une sonde qui repondrait {@code DOWN} faute de mesure retirerait l'instance de
     * la rotation sans qu'aucune panne n'ait ete constatee.
     */
    static HealthCheckResponse evaluate(String checkName, CircuitBreakerOpenWindow window,
                                        Instance<MeterRegistry> registry) {
        if (registry.isUnsatisfied()) {
            return HealthCheckResponse.builder()
                    .name(checkName)
                    .up()
                    .withData("breaker", "unobserved")
                    .build();
        }
        Duration open = window.continuouslyOpenFor(registry.get(), System.currentTimeMillis());
        boolean durablyOpen = open.compareTo(CircuitBreakerOpenWindow.DURABLY_OPEN) >= 0;
        return HealthCheckResponse.builder()
                .name(checkName)
                .status(!durablyOpen)
                .withData("breaker", open.isZero() ? "closed" : "open")
                .withData("openForMillis", open.toMillis())
                .build();
    }
}
