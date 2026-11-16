package fr.demo.souscription.gateway.client;

import fr.demo.souscription.gateway.api.dto.ProductDetail;
import fr.demo.souscription.gateway.api.dto.ProductSummary;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import java.io.IOException;
import java.util.List;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Fault tolerance boundary of the catalogue edge (W5-T01).
 *
 * <p>The source called the business tier in-process and could not fail partially; three serial
 * 99.9 % dependencies compose to about 99.7 %, so the policies below are a requirement, not an
 * optimisation (AG-014). The resources inject this bean, never {@link CatalogueClient} directly, so
 * no call can escape the policies.
 *
 * <p>Both operations are idempotent reads, hence the bounded retry. Every threshold is overridable
 * through MicroProfile Config (see {@code application.properties}); removing a timeout is never an
 * acceptable way to relax a policy. The matrix is recorded in
 * {@code docs/resilience-policies.md}.
 */
@ApplicationScoped
// Contexte de requete actif pour l'appel sortant : le filtre de propagation d'en-tetes du client
// REST lit un bean de portee requete. Un appel mis en file par le bulkhead, retente, ou repris
// apres l'abandon du client s'execute alors que le contexte entrant est deja termine : la mesure de
// charge du plan 24 a ainsi produit des 500 ContextNotActiveException qui, comptes comme echecs,
// ouvraient le disjoncteur sous une charge pourtant saine. L'intercepteur est inoperant quand un
// contexte est deja actif : il n'en cree un que dans ce cas residuel.
@ActivateRequestContext
// Le disjoncteur ne compte que les echecs qui disent la dependance injoignable ou trop lente :
// une reponse 5xx rendue promptement garde son statut public gele (502, ou 503 relaye), car
// c'est la table de docs/error-mapping.md qui en decide, pas l'etat du circuit.
@CircuitBreaker(requestVolumeThreshold = 20, failureRatio = 0.5, delay = 10_000,
        failOn = {IOException.class, ProcessingException.class, TimeoutException.class})
public class CatalogueGateway {

    /** Name used in the unavailability signal and in the journal. */
    static final String DEPENDENCY = "catalogue";

    private final CatalogueClient client;
    private final PendingRequests pending;

    @Inject
    public CatalogueGateway(@RestClient CatalogueClient client, PendingRequests pending) {
        this.client = client;
        this.pending = pending;
    }

    /**
     * The catalogue listing. A dependency that cannot be reached degrades to the unavailability
     * answer (503 + {@code Retry-After}) rather than a transport exception.
     */
    @Timeout(3000)
    @Retry(maxRetries = 2, delay = 100, jitter = 50,
            retryOn = {IOException.class, ProcessingException.class, TimeoutException.class,
                    DownstreamServerException.class})
    @Fallback(fallbackMethod = "listUnavailable",
            applyOn = {IOException.class, CircuitBreakerOpenException.class})
    @Bulkhead(value = 20, waitingTaskQueue = 20)
    @CircuitBreakerName("catalogue-list")
    public Uni<List<ProductSummary>> list() {
        return pending.track(DEPENDENCY, client.list());
    }

    /**
     * One product by code. An unknown code is a verdict on the request, not a failure of the
     * dependency: {@link DownstreamNotFoundException} is neither retried nor counted by the
     * breaker, both being restricted to transport failures and downstream 5xx.
     */
    @Timeout(3000)
    @Retry(maxRetries = 2, delay = 100, jitter = 50,
            retryOn = {IOException.class, ProcessingException.class, TimeoutException.class,
                    DownstreamServerException.class})
    @Fallback(fallbackMethod = "detailUnavailable",
            applyOn = {IOException.class, CircuitBreakerOpenException.class})
    @Bulkhead(value = 20, waitingTaskQueue = 20)
    @CircuitBreakerName("catalogue-detail")
    public Uni<ProductDetail> detail(String code) {
        return pending.track(DEPENDENCY, client.detail(code));
    }

    Uni<List<ProductSummary>> listUnavailable() {
        return Uni.createFrom().failure(new DownstreamUnavailableException(DEPENDENCY));
    }

    Uni<ProductDetail> detailUnavailable(String code) {
        return Uni.createFrom().failure(new DownstreamUnavailableException(DEPENDENCY));
    }
}
