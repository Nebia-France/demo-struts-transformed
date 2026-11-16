package fr.demo.souscription.gateway.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness: the process answers. Deliberately trivial — a degraded dependency must never restart
 * the pod (AG-007).
 */
@Liveness
@ApplicationScoped
public class GatewayLivenessCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.up("gateway-process");
    }
}
