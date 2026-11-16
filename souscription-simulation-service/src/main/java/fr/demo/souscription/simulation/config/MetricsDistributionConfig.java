package fr.demo.souscription.simulation.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Duration;

/**
 * Buckets d'histogramme du timer HTTP et du timer métier (W5-T02).
 *
 * <p>Sans buckets, le registre ne publie que _count, _sum et _max : les objectifs publiés
 * (p95 ≤ 50 ms, p99 ≤ 120 ms — NFG-001) seraient invérifiables et les règles d'alerte de
 * deploy/alerts/simulation-alerts.yml, qui utilisent histogram_quantile sur
 * http_server_requests_seconds_bucket, n'auraient aucune série à évaluer.
 *
 * <p>Des buckets côté serveur plutôt que des percentiles côté client : les percentiles
 * pré-calculés ne sont pas agrégeables entre les ≥ 2 répliques exigées par l'objectif de
 * 99,9 %. Les SLO 50 ms / 120 ms sont déclarés explicitement pour que les bornes utiles
 * existent sans élargir l'espace de séries au-delà des buckets par défaut.
 */
@Singleton
public class MetricsDistributionConfig {

    @Produces
    @Singleton
    public MeterFilter latencyHistograms() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if ("http.server.requests".equals(id.getName())
                        || "simulation.calculate".equals(id.getName())) {
                    return DistributionStatisticConfig.builder()
                            .percentilesHistogram(true)
                            .serviceLevelObjectives(
                                    Duration.ofMillis(50).toNanos(),
                                    Duration.ofMillis(120).toNanos())
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }
}
