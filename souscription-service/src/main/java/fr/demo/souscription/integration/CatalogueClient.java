package fr.demo.souscription.integration;

import fr.demo.souscription.api.dto.ProductDetail;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import io.vertx.core.VertxException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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
 * Client REST du contrat consomme {@code GET /v1/products/{code}} de
 * souscription-catalogue-service
 * ({@code global/contracts/souscription-service-to-souscription-catalogue-service/openapi.yaml}).
 *
 * <p>Remplace la lecture en memoire le service de catalogue en memoire du source (W6-T01) : le taux
 * annuel persiste dans la souscription est toujours celui retourne par le catalogue, jamais un
 * taux par defaut, replique ou cache.
 *
 * <p>Les parametres de resilience sont <b>imposes</b> par
 * {@code resilience-contract.json} et reproduits a l'identique : timeout 3 s, 2 reessais
 * (backoff exponentiel avec gigue, base 100 ms, plafond 1 s), disjoncteur
 * (volume 20, ratio 0,5, delai 10 s, seuil de retablissement 2), cloison 20 appels concurrents.
 *
 * <p>Conditions non rejouables du contrat : toute reponse 4xx et le 429. Elles sont converties par
 * {@link CatalogueResponseMapper} en exceptions {@link CatalogueClientException}, absentes de
 * {@code retryOn} : un 404 ou un 429 n'est jamais reessaye.
 *
 * <p>La traduction en statuts de ce service (404 produit inconnu, 503 catalogue indisponible) est
 * portee par {@link CatalogueLookup} : aucun 4xx distant ne devient un 5xx local.
 */
@RegisterRestClient(configKey = "catalogue")
@RegisterProvider(CatalogueResponseMapper.class)
@Path("/v1/products")
@Produces(MediaType.APPLICATION_JSON)
public interface CatalogueClient {

    @GET
    @Path("/{code}")
    @Timeout(3000)
    @Retry(maxRetries = 2, delay = 100, jitter = 100,
            retryOn = {IOException.class, ProcessingException.class, WebApplicationException.class,
                    VertxException.class})
    @ExponentialBackoff(maxDelay = 1000)
    @CircuitBreaker(requestVolumeThreshold = 20, failureRatio = 0.5,
            delay = 10_000, successThreshold = 2)
    @Bulkhead(20)
    ProductDetail findProduct(@PathParam("code") String code);
}
