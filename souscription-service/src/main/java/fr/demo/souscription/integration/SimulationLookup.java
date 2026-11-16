package fr.demo.souscription.integration;

import fr.demo.souscription.api.dto.SimulationResult;
import fr.demo.souscription.application.SimulationUnavailableException;
import jakarta.enterprise.context.ApplicationScoped;
import io.vertx.core.VertxException;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Seule porte d'entree de la simulation pour les ressources et les services applicatifs (W6-T02).
 *
 * <p>Traduit les echecs du client distant en statuts du contrat de ce service :
 * <ul>
 *   <li>parametres refuses par le moteur de calcul (400 distant) :
 *       {@link SimulationRejectedException}, rendu <b>400</b> par l'appelant — un 4xx distant ne
 *       devient jamais un 5xx local ;</li>
 *   <li>simulation indisponible (timeout, 5xx, disjoncteur ouvert, cloison saturee, 429) :
 *       {@link SimulationUnavailableException}, rendue <b>503 rejouable</b>. Aucune projection de
 *       repli n'est jamais servie : ni recalcul local, ni valeur fournie par le client
 *       ({@code fallback} du contrat de resilience).</li>
 * </ul>
 *
 * <p>Aucun appel n'est effectue depuis une methode {@code @Transactional} : le recalcul precede
 * l'ouverture de la transaction de confirmation (NFG-009).
 *
 * <p>Le client REST distant est la seule implementation : aucun repli local n'existe.
 */
@ApplicationScoped
public class SimulationLookup {

    private static final Logger LOG = Logger.getLogger(SimulationLookup.class);

    private final SimulationClient remote;

    @Inject
    public SimulationLookup(@RestClient SimulationClient remote) {
        this.remote = remote;
    }

    /**
     * Recalcul autoritaire de la projection. La valeur retournée est celle du fournisseur, elle
     * est persistee telle quelle par la confirmation.
     */
    public SimulationResult compute(SimulationComputeRequest request) {
        try {
            return remote.compute(request);
        } catch (SimulationRejectedException refused) {
            throw refused;
        } catch (SimulationClientException nonRetryable) {
            throw unavailable(nonRetryable,
                    "appel a la simulation refuse ; status=" + nonRetryable.status());
        } catch (CircuitBreakerOpenException open) {
            throw unavailable(open, "disjoncteur de la simulation ouvert");
        } catch (BulkheadException saturated) {
            throw unavailable(saturated, "appels concurrents a la simulation satures");
        } catch (TimeoutException expired) {
            throw unavailable(expired, "delai d'appel a la simulation depasse");
        } catch (WebApplicationException | ProcessingException | VertxException failure) {
            throw unavailable(failure, "simulation injoignable");
        }
    }

    /**
     * Une simulation indisponible a exactement la semantique d'un stockage indisponible : aucune
     * confirmation n'est emise, la saisie est conservee et la requete peut etre rejouee. Jamais de
     * 500, et jamais de confirmation avec une valeur non verifiee.
     */
    private static SimulationUnavailableException unavailable(Throwable cause, String reason) {
        LOG.warnf(cause, "Simulation indisponible ; raison=%s", reason);
        return new SimulationUnavailableException(reason, cause);
    }
}
