package fr.demo.souscription.integration;

/**
 * Reponse 4xx du catalogue : condition <b>non rejouable</b> du contrat de resilience
 * ({@code nonRetryableConditions} : toute reponse 4xx et le 429).
 *
 * <p>N'herite volontairement pas de {@code WebApplicationException} : le {@code retryOn} de
 * {@link CatalogueClient} cite cette derniere, et un 4xx reessaye violerait le contrat.
 */
public class CatalogueClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public CatalogueClientException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
