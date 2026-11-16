package fr.demo.souscription.gateway.health;

import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;

/**
 * Current circuit breaker state per downstream edge, read from the SmallRye Fault Tolerance
 * registry (W6-T01). No call is made to the downstreams: the state is the one the gateway already
 * holds from its real traffic.
 *
 * <p>Each gateway method owns its own breaker (named by {@code @CircuitBreakerName} on the
 * {@code *Gateway} beans); the state reported for an edge is the worst of its breakers, in the order
 * {@code OPEN} &gt; {@code HALF_OPEN} &gt; {@code CLOSED}.
 */
@ApplicationScoped
public class CircuitBreakerStates {

    static final Map<String, List<String>> BREAKERS = Map.of(
            "catalogue", List.of("catalogue-list", "catalogue-detail"),
            "simulation", List.of("simulation-compute"),
            "subscription", List.of("subscription-startDraft", "subscription-getDraft",
                    "subscription-putSubscriber", "subscription-putSimulation",
                    "subscription-confirm", "subscription-getSubscription"));

    private final CircuitBreakerMaintenance maintenance;

    @Inject
    public CircuitBreakerStates(CircuitBreakerMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    /** Worst breaker state of the downstream edge {@code dependency}. */
    public CircuitBreakerState of(String dependency) {
        List<String> names = BREAKERS.get(dependency);
        if (names == null) {
            throw new IllegalArgumentException("Unknown downstream edge: " + dependency);
        }
        CircuitBreakerState worst = CircuitBreakerState.CLOSED;
        for (String name : names) {
            CircuitBreakerState state = maintenance.currentState(name);
            if (rank(state) > rank(worst)) {
                worst = state;
            }
        }
        return worst;
    }

    private static int rank(CircuitBreakerState state) {
        return switch (state) {
            case OPEN -> 2;
            case HALF_OPEN -> 1;
            case CLOSED -> 0;
        };
    }
}
