package fr.demo.souscription.gateway.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness: the gateway's own capacity to serve its routes. It never looks at the downstreams —
 * failing readiness on a downstream outage would turn a partial failure into a total outage of the
 * public entry point (AG-007, NFG-013).
 */
@Readiness
@ApplicationScoped
public class GatewayReadinessCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.up("gateway-routes");
    }
}
