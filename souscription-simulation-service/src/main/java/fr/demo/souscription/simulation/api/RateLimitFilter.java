package fr.demo.souscription.simulation.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Per-caller rate limiting (W6-T04), as declared in the {@code rateLimiting} block of both frozen
 * security policies: 100 requests/s with a burst of 200, scoped <em>per calling workload
 * identity</em>, answered with {@code 429 application/problem+json} when exceeded.
 *
 * <p>No service mesh is in place in this topology, so the limit is enforced in process. It is a
 * token bucket, not a queue: an excess request is refused immediately, never parked. An unbounded
 * queue would convert a rate breach into unbounded latency, which the caller cannot distinguish
 * from a hang.
 *
 * <p>The two rejection paths are deliberately distinct and must not be conflated:
 * <ul>
 *   <li>{@code 503} — bulkhead or timeout: the service is momentarily saturated, and the frozen
 *       resilience contracts allow the caller a single retry;</li>
 *   <li>{@code 429} — this filter: the caller is above its contracted rate, and
 *       {@code retry.nonRetryableConditions} forbids retrying it. No {@code Retry-After} is sent,
 *       precisely because the contracted behaviour is not to retry.</li>
 * </ul>
 *
 * <p>The bucket key is the SPIFFE identity resolved by {@link WorkloadIdentityFilter}, which runs
 * first and has already rejected every uncontracted identity: the map is therefore bounded by the
 * size of the allow-list, never by the traffic. Both figures are configuration, so a contract
 * change is a redeploy and the load-measurement campaign can raise them without a code change.
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 10)
public class RateLimitFilter implements ContainerRequestFilter {

    static final String TITLE = "Too Many Requests";
    /** Frozen text: no internal figure (rate, burst, remaining tokens) is disclosed (NFG-008). */
    static final String DETAIL = "Débit contractuel dépassé pour l'identité appelante";

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final Counter rejected;

    @ConfigProperty(name = "simulation.ratelimit.requests-per-second")
    double requestsPerSecond;

    @ConfigProperty(name = "simulation.ratelimit.burst")
    double burst;

    @Context
    HttpHeaders headers;

    @Inject
    public RateLimitFilter(MeterRegistry registry) {
        this.rejected = Counter.builder("simulation.ratelimit.rejected")
                .description("Requests refused with 429 because the calling identity exceeded its contracted rate")
                .register(registry);
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String identity = String.valueOf(
                requestContext.getProperty(WorkloadIdentityFilter.CALLER_IDENTITY));
        TokenBucket bucket = buckets.computeIfAbsent(identity,
                key -> new TokenBucket(burst, requestsPerSecond));
        if (!bucket.tryAcquire(System.nanoTime())) {
            rejected.increment();
            requestContext.abortWith(ProblemJson.response(429, TITLE, DETAIL, null, headers));
        }
    }

    /** Token bucket: {@code burst} tokens of capacity, refilled at {@code refillPerSecond}. */
    private static final class TokenBucket {

        private final double capacity;
        private final double refillPerNano;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(double capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerNano = refillPerSecond / NANOS_PER_SECOND;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        /** Called from every worker thread serving this identity, hence synchronized. */
        synchronized boolean tryAcquire(long nowNanos) {
            long elapsed = nowNanos - lastRefillNanos;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
                lastRefillNanos = nowNanos;
            }
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }
    }
}
