package fr.demo.souscription.integration;

import fr.demo.souscription.api.dto.SimulationResult;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import io.vertx.core.VertxException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Client REST du contrat consomme {@code POST /v1/simulations} de
 * souscription-simulation-service
 * ({@code global/contracts/souscription-service-to-souscription-simulation-service/openapi.yaml}).
 *
 * <p>Remplace le calcul en memoire du service de simulation du source (W6-T02) : la projection
 * persistee est toujours celle que retourne le fournisseur, jamais une valeur fournie par le
 * client ni un calcul local de repli.
 *
 * <p>Les parametres de resilience sont <b>imposes</b> par
 * {@code resilience-contract.json} et reproduits a l'identique : timeout 3 s, 1 reessai
 * (backoff exponentiel avec gigue, base 100 ms, plafond 1 s), disjoncteur
 * (volume 20, ratio 0,5, delai 10 s, seuil de retablissement 2), cloison 20 appels concurrents.
 *
 * <p>Conditions non rejouables du contrat : toute reponse 4xx et le 429, converties par
 * {@link SimulationResponseMapper} en {@link SimulationClientException}, absente de
 * {@code retryOn}.
 *
 * <p>La traduction en statuts de ce service (400 parametres refuses, 503 simulation
 * indisponible) est portee par {@link SimulationLookup}.
 */
@RegisterRestClient(configKey = "simulation")
@RegisterProvider(SimulationResponseMapper.class)
@Path("/v1/simulations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface SimulationClient {

    /**
     * Recalcul autoritaire de la projection, effectue <b>avant</b> l'ouverture de la transaction
     * de confirmation (NFG-009).
     *
     * <p><b>Surete du reessai sur un POST :</b> le reessai est admissible uniquement parce que
     * l'operation est un calcul pur, sans effet de bord cote fournisseur — rejouer la requete ne
     * cree ni ne modifie rien. Si souscription-simulation-service acquiert un jour le moindre
     * effet de bord sur cette operation (enregistrement, compteur persistant, emission
     * d'evenement), {@code maxRetries = 1} devient incorrect et doit etre ramene a 0 : un POST
     * non idempotent reessaye produirait deux effets pour une seule confirmation.
     */
    @POST
    @Timeout(3000)
    @Retry(maxRetries = 1, delay = 100, jitter = 100,
            retryOn = {IOException.class, ProcessingException.class, WebApplicationException.class,
                    VertxException.class})
    @ExponentialBackoff(maxDelay = 1000)
    @CircuitBreaker(requestVolumeThreshold = 20, failureRatio = 0.5,
            delay = 10_000, successThreshold = 2)
    @Bulkhead(20)
    SimulationResult compute(SimulationComputeRequest request);
}
