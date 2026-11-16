package fr.demo.souscription.integration;

/**
 * Code produit inconnu du catalogue (404 distant). Traduit par {@link CatalogueLookup} en
 * l'absence de produit, donc en 404 declare par {@code startDraft} sur le contrat de ce
 * service : un 4xx distant ne devient jamais un 5xx local.
 */
public class CatalogueNotFoundException extends CatalogueClientException {

    private static final long serialVersionUID = 1L;

    public CatalogueNotFoundException(String message) {
        super(404, message);
    }
}
