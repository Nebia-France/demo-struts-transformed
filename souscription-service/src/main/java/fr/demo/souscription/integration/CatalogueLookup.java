package fr.demo.souscription.integration;

import fr.demo.souscription.api.dto.ProductDetail;
import io.vertx.core.VertxException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.WebApplicationException;
import java.util.Optional;
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Resolution du produit pour le parcours (W6-T01) : seule porte d'entree du catalogue pour les
 * ressources et les services applicatifs.
 *
 * <p>Traduit les echecs du client distant en statuts du contrat de ce service :
 * <ul>
 *   <li>produit inconnu (404 du catalogue) : {@link Optional#empty()}, rendu en <b>404</b>
 *       {@code startDraft} par l'appelant — un 4xx distant ne devient jamais un 5xx local ;</li>
 *   <li>catalogue indisponible (timeout, 5xx, disjoncteur ouvert, cloison saturee, 429) :
 *       <b>503</b>, aucune valeur par defaut et aucun taux cache n'est substitue
 *       ({@code fallback} du contrat de resilience).</li>
 * </ul>
 *
 * <p>Aucun appel n'est effectue depuis une methode {@code @Transactional} : la resolution precede
 * l'ouverture de la transaction (NFG-009), cf. {@code INTEGRATION_DECISIONS.md}.
 *
 * <p>Le client REST distant est la seule implementation : aucun repli local n'existe.
 */
@ApplicationScoped
public class CatalogueLookup {

    private static final Logger LOG = Logger.getLogger(CatalogueLookup.class);

    private final CatalogueClient remote;

    @Inject
    public CatalogueLookup(@RestClient CatalogueClient remote) {
        this.remote = remote;
    }

    /**
     * Recherche du produit par code. Un code absent ou inconnu ne provoque aucune exception :
     * la recherche en memoire du source renvoyait simplement aucun produit.
     */
    public Optional<ProductDetail> findProduct(String code) {
        if (code == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(remote.findProduct(code));
        } catch (CatalogueNotFoundException unknown) {
            LOG.debugf("Produit inconnu au catalogue ; code=%s", code);
            return Optional.empty();
        } catch (CatalogueClientException refused) {
            throw unavailable(refused, "appel au catalogue refuse ; status=" + refused.status());
        } catch (CircuitBreakerOpenException open) {
            throw unavailable(open, "disjoncteur du catalogue ouvert");
        } catch (BulkheadException saturated) {
            throw unavailable(saturated, "appels concurrents au catalogue satures");
        } catch (TimeoutException expired) {
            throw unavailable(expired, "delai d'appel au catalogue depasse");
        } catch (WebApplicationException | ProcessingException | VertxException failure) {
            throw unavailable(failure, "catalogue injoignable");
        }
    }

    /**
     * Le catalogue est la seule source du taux annuel : son indisponibilite est un 503 rejouable
     * et jamais un 500, et aucun taux de repli n'est jamais servi.
     */
    private static ServiceUnavailableException unavailable(Throwable cause, String reason) {
        LOG.warnf(cause, "Catalogue indisponible ; raison=%s", reason);
        return new ServiceUnavailableException();
    }
}
