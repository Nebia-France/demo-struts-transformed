package fr.demo.souscription.simulation.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness sur la saturation du pool de workers (W5-T01).
 *
 * <p>La profondeur de file est lue sur la jauge Micrometer {@code worker.pool.queue.size}
 * (exposée {@code worker_pool_queue_size} en Prometheus) et comparée à
 * {@code quarkus.thread-pool.queue-size}. File pleine = la réplique est retirée du trafic
 * par la readiness, jamais tuée : la liveness reste UP, sinon un pic de charge provoquerait
 * un redémarrage en boucle.
 *
 * <p>Ce check ne dépend d'<b>aucune</b> autre application : le service n'a pas de base de
 * données, pas d'appel sortant et ne lit pas le catalogue. Une readiness dépendant d'un
 * tiers fabriquerait une panne fictive.
 */
@Readiness
@ApplicationScoped
public class WorkerPoolHealthCheck implements HealthCheck {

    static final String NAME = "simulation-worker-pool";

    /** Nom du meter Micrometer, et sa forme Prometheus, selon le binder Vert.x utilisé. */
    private static final String[] QUEUE_SIZE_METERS = {
        "worker.pool.queue.size", "worker_pool_queue_size"
    };

    private final MeterRegistry registry;
    private final int queueCapacity;

    @Inject
    public WorkerPoolHealthCheck(MeterRegistry registry,
                                 @ConfigProperty(name = "quarkus.thread-pool.queue-size",
                                                 defaultValue = "64") int queueCapacity) {
        this.registry = registry;
        this.queueCapacity = queueCapacity;
    }

    @Override
    public HealthCheckResponse call() {
        int queued = queueDepth();
        HealthCheckResponseBuilder response = HealthCheckResponse.named(NAME)
                .withData("queueSize", queued)
                .withData("queueCapacity", queueCapacity);
        return queued >= queueCapacity ? response.down().build() : response.up().build();
    }

    /**
     * Profondeur de file instantanée, ou 0 si la jauge n'est pas (encore) enregistrée :
     * une métrique absente n'est pas une saturation.
     */
    int queueDepth() {
        double max = 0;
        for (String meterName : QUEUE_SIZE_METERS) {
            for (Gauge gauge : registry.find(meterName).gauges()) {
                double value = gauge.value();
                if (!Double.isNaN(value) && value > max) {
                    max = value;
                }
            }
        }
        return (int) max;
    }
}
