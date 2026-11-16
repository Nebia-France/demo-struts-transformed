package fr.demo.souscription.integration;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/**
 * Traduction des reponses d'erreur du catalogue en exceptions dont le caractere rejouable est
 * explicite (W6-T01) :
 * <ul>
 *   <li>404 : {@link CatalogueNotFoundException} — produit inconnu, non rejouable ;</li>
 *   <li>autre 4xx, dont 429 : {@link CatalogueClientException} — non rejouable ;</li>
 *   <li>5xx : {@link WebApplicationException} — seule famille citee par le {@code retryOn} de
 *       {@link CatalogueClient}, donc la seule reessayee.</li>
 * </ul>
 *
 * <p>Le corps {@code problem+json} du catalogue n'est pas recopie dans l'exception : il n'est pas
 * destine a l'appelant de ce service.
 */
public class CatalogueResponseMapper implements ResponseExceptionMapper<RuntimeException> {

    @Override
    public boolean handles(int status, MultivaluedMap<String, Object> headers) {
        return status >= 400;
    }

    @Override
    public RuntimeException toThrowable(Response response) {
        int status = response.getStatus();
        if (status == 404) {
            return new CatalogueNotFoundException("produit inconnu au catalogue");
        }
        if (status < 500) {
            return new CatalogueClientException(status,
                    "appel au catalogue refuse ; status=" + status);
        }
        return new WebApplicationException("catalogue en echec ; status=" + status, status);
    }
}
