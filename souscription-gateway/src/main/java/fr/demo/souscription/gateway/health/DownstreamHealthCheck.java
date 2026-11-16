package fr.demo.souscription.gateway.health;

import io.smallrye.health.api.Wellness;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;

/**
 * Dependency health: informative, NEVER blocking. Published under {@code /q/health/well} (and the
 * aggregate {@code /q/health}), never under {@code live} or {@code ready}, and always UP: the data
 * carries the breaker state of each downstream edge, read from the fault tolerance registry
 * without any extra probe call.
 */
@Wellness
@ApplicationScoped
public class DownstreamHealthCheck implements HealthCheck {

    @Inject
    CircuitBreakerStates states;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("downstream").up()
                .withData("catalogue", states.of("catalogue").name())
                .withData("simulation", states.of("simulation").name())
                .withData("subscription", states.of("subscription").name())
                .build();
    }
}
