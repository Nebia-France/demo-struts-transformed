package fr.demo.souscription.gateway.client;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-edge count of outbound requests sent and still waiting for their response, exported as
 * {@code http_client_pending_requests{clientName=...}} (W5-T04).
 *
 * <p>The Vert.x binder of quarkus-micrometer 3.20.1 builds an {@code http.client.pending} gauge but
 * never registers it, so the pool-level signal used to prove that a slow dependency does not
 * exhaust the connections of the healthy edges is published here. The count is taken around the
 * REST client call itself, below the bulkhead: a call queued by the bulkhead is not counted, a call
 * holding a connection is, until it completes, fails or is cancelled by its timeout.
 */
@ApplicationScoped
public class PendingRequests {

    static final String METRIC = "http.client.pending.requests";

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> pending = new ConcurrentHashMap<>();

    @Inject
    public PendingRequests(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Tracks the outbound call {@code call} on the edge {@code clientName}. */
    public <T> Uni<T> track(String clientName, Uni<T> call) {
        AtomicLong counter = counter(clientName);
        return call
                .onSubscription().invoke(counter::incrementAndGet)
                .onTermination().invoke(counter::decrementAndGet);
    }

    private AtomicLong counter(String clientName) {
        return pending.computeIfAbsent(clientName, name -> {
            AtomicLong counter = new AtomicLong();
            Gauge.builder(METRIC, counter, AtomicLong::get)
                    .description("Outbound requests waiting for a response, per downstream edge")
                    .tag("clientName", name)
                    .strongReference(true)
                    .register(registry);
            return counter;
        });
    }
}
