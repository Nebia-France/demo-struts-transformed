package fr.demo.souscription.gateway.edge;

/**
 * Failure of the double-submit CSRF check at the edge (W3-T03).
 *
 * <p>Carries no detail about the rejected pair on purpose: the six rejection cases (cookie absent,
 * header absent, both absent, differing values, oversized header, expired or unverifiable token)
 * must be indistinguishable from the outside, so an attacker learns nothing about which half of the
 * pair was wrong. The cause is logged server side by the filter, never serialised.
 *
 * <p>Mapped to 403 {@code problem+json} by {@code ProblemFactory}, exactly as the source filter
 * dispatched its 403 error page.
 */
public class CsrfValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CsrfValidationException() {
        super("CSRF double-submit validation failed");
    }
}
