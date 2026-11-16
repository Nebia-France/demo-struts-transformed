package fr.demo.souscription.catalogue.health;

import fr.demo.souscription.catalogue.service.CatalogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness probe (W5-T01). Turns UP only once the in-memory catalogue has been seeded by the
 * StartupEvent observer, so the orchestrator never routes traffic to an empty catalogue.
 *
 * No remote dependency is consulted on purpose: the service has no datastore and no outbound
 * call, and a probe depending on a third party causes cascading restarts.
 */
@Readiness
@ApplicationScoped
public class CatalogueReadinessCheck implements HealthCheck {

    @Inject
    CatalogService catalogService;

    @Override
    public HealthCheckResponse call() {
        int size = catalogService.getProducts().size();
        return HealthCheckResponse.named("catalogue-seed")
                .status(size > 0)
                .withData("products", size)
                .build();
    }
}
