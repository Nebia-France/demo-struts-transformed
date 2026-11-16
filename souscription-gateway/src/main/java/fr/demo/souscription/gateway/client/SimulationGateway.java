package fr.demo.souscription.gateway.client;

import fr.demo.souscription.gateway.api.dto.SimulationResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import java.io.IOException;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Fault tolerance boundary of the simulation edge (W5-T01).
 *
 * <p>Its own circuit breaker isolates a failing simulation service from the catalogue and from
 * souscription-service, so each dependency fails on its own.
 *
 * <p>The projection is a pure computation with no side effect, so a single retry is declared. No
 * fallback is declared: a made-up projection would be worse than an error, the figures being shown
 * to the subscriber before they commit.
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
public class SimulationGateway {

    private final SimulationClient client;
    private final PendingRequests pending;

    @Inject
    public SimulationGateway(@RestClient SimulationClient client, PendingRequests pending) {
        this.client = client;
        this.pending = pending;
    }

    @Timeout(3000)
    @Retry(maxRetries = 1, delay = 100, jitter = 50,
            retryOn = {IOException.class, ProcessingException.class, TimeoutException.class,
                    DownstreamServerException.class})
    @Bulkhead(value = 20, waitingTaskQueue = 20)
    @CircuitBreakerName("simulation-compute")
    public Uni<SimulationResult> compute(SimulationComputeRequest request) {
        return pending.track("simulation", client.compute(request));
    }
}
