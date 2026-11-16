package fr.demo.souscription.simulation.health;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

/**
 * Pas de @QuarkusTest : le check est un POJO à deux dépendances (registre Micrometer et
 * capacité de file), donc la saturation est pilotable sans démarrer l'application.
 */
class WorkerPoolHealthCheckTest {

    private static final int QUEUE_CAPACITY = 64;

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicInteger queueSize = new AtomicInteger();
    private final WorkerPoolHealthCheck check =
            new WorkerPoolHealthCheck(registry, QUEUE_CAPACITY);

    WorkerPoolHealthCheckTest() {
        Gauge.builder("worker.pool.queue.size", queueSize, AtomicInteger::doubleValue)
                .tag("pool.type", "worker")
                .tag("pool.name", "vert.x-worker-thread")
                .register(registry);
    }

    @Test
    void upWhenQueueBelowCapacity() {
        queueSize.set(QUEUE_CAPACITY - 1);

        HealthCheckResponse response = check.call();

        assertEquals("simulation-worker-pool", response.getName());
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    }

    @Test
    void upWhenQueueEmpty() {
        queueSize.set(0);

        assertEquals(HealthCheckResponse.Status.UP, check.call().getStatus());
    }

    @Test
    void downWhenQueueSaturated() {
        queueSize.set(QUEUE_CAPACITY);

        HealthCheckResponse response = check.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        assertEquals("64", response.getData().orElseThrow().get("queueSize").toString());
    }

    @Test
    void downWhenQueueOverflows() {
        queueSize.set(QUEUE_CAPACITY * 2);

        assertEquals(HealthCheckResponse.Status.DOWN, check.call().getStatus());
    }

    @Test
    void absentGaugeIsNotSaturation() {
        MeterRegistry empty = new SimpleMeterRegistry();

        assertEquals(HealthCheckResponse.Status.UP,
                new WorkerPoolHealthCheck(empty, QUEUE_CAPACITY).call().getStatus());
    }
}
