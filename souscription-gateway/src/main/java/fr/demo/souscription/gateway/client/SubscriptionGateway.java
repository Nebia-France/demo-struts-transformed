package fr.demo.souscription.gateway.client;

import fr.demo.souscription.gateway.api.dto.ConfirmationRequest;
import fr.demo.souscription.gateway.api.dto.DraftStartRequest;
import fr.demo.souscription.gateway.api.dto.DraftView;
import fr.demo.souscription.gateway.api.dto.SimulationInput;
import fr.demo.souscription.gateway.api.dto.SubscriberInput;
import fr.demo.souscription.gateway.api.dto.SubscriptionConfirmation;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import java.io.IOException;
import java.util.UUID;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Fault tolerance boundary of the souscription-service edge (W5-T01).
 *
 * <p>Timeout budget of 5000 ms on every operation: the downstream confirmation covers a product
 * read, the remote simulation and the write.
 *
 * <p>The retry policy is asymmetric on purpose. The draft reads are idempotent and carry a bounded
 * retry; the writes carry <strong>none</strong>. From the transport's point of view the outcome of a
 * timed-out POST is unknown, so a transparent retry could duplicate a user action even though the
 * provider is idempotent on the confirmation key (W4-T02): the timeout surfaces as 504 and the
 * caller re-submits the same key. For the same reason no fallback is declared anywhere on this
 * bean — a silent fallback on a write masks data loss.
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
public class SubscriptionGateway {

    private final SubscriptionClient client;
    private final PendingRequests pending;

    @Inject
    public SubscriptionGateway(@RestClient SubscriptionClient client, PendingRequests pending) {
        this.client = client;
        this.pending = pending;
    }

    /** Draft creation: a write, therefore no retry. */
    @Timeout(5000)
    @Bulkhead(value = 20, waitingTaskQueue = 20)
    @CircuitBreakerName("subscription-startDraft")
    public Uni<DraftView> startDraft(DraftStartRequest request) {
        return pending.track("subscription", client.startDraft(request));
    }

    @Timeout(5000)
    @Retry(maxRetries = 1, delay = 100, jitter = 50,
            retryOn = {IOException.class, ProcessingException.class, TimeoutException.class,
                    DownstreamServerException.class})
    @Bulkhead(value = 20, waitingTaskQueue = 20)
    @CircuitBreakerName("subscription-getDraft")
    public Uni<DraftView> getDraft(UUID draftId) {
        return pending.track("subscription", client.getDraft(draftId));
    }

    /** Draft step: a write, therefore no retry. */
    @Timeout(5000)
    @Bulkhead(value = 20, waitingTaskQueue = 20)
    @CircuitBreakerName("subscription-putSubscriber")
    public Uni<DraftView> putSubscriber(UUID draftId, SubscriberInput subscriber) {
        return pending.track("subscription", client.putSubscriber(draftId, subscriber));
    }

    /** Draft step: a write, therefore no retry. */
    @Timeout(5000)
    @Bulkhead(value = 20, waitingTaskQueue = 20)
    @CircuitBreakerName("subscription-putSimulation")
    public Uni<DraftView> putSimulation(UUID draftId, SimulationInput simulation) {
        return pending.track("subscription", client.putSimulation(draftId, simulation));
    }

    /**
     * The one write edge of the gateway. Carries a timeout only: no {@code @Retry} and no
     * {@code @Fallback}, deliberately — see the class javadoc and
     * {@code docs/resilience-policies.md}.
     */
    @Timeout(5000)
    @Bulkhead(value = 20, waitingTaskQueue = 20)
    @CircuitBreakerName("subscription-confirm")
    public Uni<RestResponse<SubscriptionConfirmation>> confirm(UUID draftId,
                                                               String confirmationKey,
                                                               ConfirmationRequest request) {
        return pending.track("subscription", client.confirm(draftId, confirmationKey, request));
    }

    @Timeout(5000)
    @Retry(maxRetries = 1, delay = 100, jitter = 50,
            retryOn = {IOException.class, ProcessingException.class, TimeoutException.class,
                    DownstreamServerException.class})
    @Bulkhead(value = 20, waitingTaskQueue = 20)
    @CircuitBreakerName("subscription-getSubscription")
    public Uni<SubscriptionConfirmation> getSubscription(String reference) {
        return pending.track("subscription", client.getSubscription(reference));
    }
}
