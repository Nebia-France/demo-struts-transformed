package fr.demo.souscription.gateway.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Set;

/**
 * Publishes a bounded set of latency buckets on the server and client request timers (W6-T02), so
 * the dashboard and the latency alerts can compute p95/p99 per route and per downstream with
 * {@code histogram_quantile} across replicas.
 *
 * <p>Explicit boundaries are used instead of Micrometer's percentile histogram (about 70 buckets
 * per series): ten buckets keep the cardinality bounded, and they are aligned on the gateway's own
 * budgets — the 3 s and 5 s downstream timeouts and the 10 s shutdown drain.
 */
@Singleton
public class LatencyHistograms {

    static final Set<String> TIMERS = Set.of("http.server.requests", "http.client.requests");

    static final double[] BOUNDARIES_SECONDS = {0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 3, 5, 10};

    @Produces
    @Singleton
    MeterFilter requestLatencyBuckets() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!TIMERS.contains(id.getName())) {
                    return config;
                }
                double[] nanos = new double[BOUNDARIES_SECONDS.length];
                for (int i = 0; i < nanos.length; i++) {
                    nanos[i] = Duration.ofMillis(Math.round(BOUNDARIES_SECONDS[i] * 1000)).toNanos();
                }
                return DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(nanos)
                        .build()
                        .merge(config);
            }
        };
    }
}
