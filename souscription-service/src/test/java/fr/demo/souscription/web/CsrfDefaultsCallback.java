package fr.demo.souscription.web;

import io.quarkus.test.junit.callback.QuarkusTestBeforeEachCallback;
import io.quarkus.test.junit.callback.QuarkusTestMethodContext;
import io.restassured.RestAssured;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

/**
 * Paire anti-CSRF par defaut pour les tests HTTP (W7-T02).
 *
 * <p>{@link CsrfDefenceFilter} refuse toute methode non sure depourvue d'une paire en-tete /
 * cookie concordante. Les tests des vagues 2 a 6 ont ete ecrits avant ce controle et emettent
 * leurs POST et PUT sans jeton : plutot que de modifier 149 appels, ce rappel installe une paire
 * valide par defaut sur toute requete REST Assured qui n'en porte pas, comme le fait la
 * passerelle en production.
 *
 * <p>Les tests du controle lui-meme doivent pouvoir emettre une requete sans jeton, ou avec un
 * jeton volontairement incorrect : ils posent l'en-tete {@link #OPT_OUT}, qui desarme
 * l'injection et est retire avant l'emission. Aucune valeur par defaut n'est donc injectee a leur
 * insu, et le controle n'est jamais desactive cote service.
 */
public class CsrfDefaultsCallback implements QuarkusTestBeforeEachCallback {

    /** En-tete de desarmement, consomme et retire par le filtre REST Assured. */
    public static final String OPT_OUT = "X-Csrf-Default-Off";

    /** Jeton utilise par defaut par les tests qui ne s'interessent pas au controle CSRF. */
    public static final String DEFAULT_TOKEN = "jeton-de-test-csrf";

    @Override
    public void beforeEach(QuarkusTestMethodContext context) {
        boolean installed = RestAssured.filters().stream()
                .anyMatch(filter -> filter instanceof DoubleSubmitDefaults);
        if (!installed) {
            RestAssured.filters(new DoubleSubmitDefaults());
        }
    }

    /** Pose la paire double-submit quand la requete n'en porte pas deja une. */
    public static final class DoubleSubmitDefaults implements Filter {

        @Override
        public Response filter(FilterableRequestSpecification requestSpec,
                               FilterableResponseSpecification responseSpec,
                               FilterContext ctx) {
            if (requestSpec.getHeaders().hasHeaderWithName(OPT_OUT)) {
                requestSpec.removeHeader(OPT_OUT);
            } else if (!requestSpec.getHeaders().hasHeaderWithName(CsrfDefenceFilter.TOKEN_HEADER)) {
                requestSpec.header(CsrfDefenceFilter.TOKEN_HEADER, DEFAULT_TOKEN);
                requestSpec.cookie(CsrfDefenceFilter.TOKEN_COOKIE, DEFAULT_TOKEN);
            }
            return ctx.next(requestSpec, responseSpec);
        }
    }
}
