package fr.demo.souscription.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/**
 * Verification anti-CSRF secondaire de souscription-service (W7-T02).
 *
 * <p>La protection primaire est portee par {@code souscription-gateway} : cookie double-submit
 * {@code SameSite=Strict}, pose et valide sur la meme origine que la SPA. Ce filtre est une
 * defense en profondeur : il revalide la paire en-tete / cookie transmise par la passerelle sur
 * toute methode HTTP non sure, afin qu'une requete non controlee atteignant directement le
 * service soit refusee et non servie.
 *
 * <p>Le service reste sans cookie et sans session : ce filtre ne cree ni cookie, ni session, ni
 * contexte de securite, et aucun etat serveur n'est etabli depuis le cookie — la valeur lue est
 * uniquement comparee a l'en-tete. L'authentification du service demeure le mTLS de maillage.
 * Quand la paire est absente, la requete est refusee en 403, jamais acceptee silencieusement.
 *
 * <p>Deux comportements du filtre de securite servlet de la source sont conserves a
 * l'identique : la comparaison en temps constant des deux jetons (jamais {@code String.equals})
 * et la borne de longueur de 100 caracteres au-dela de laquelle le jeton est refuse.
 */
@ApplicationScoped
public class CsrfDefenceFilter {

    /** En-tete porteur du jeton, emis par la SPA et relaye par la passerelle. */
    public static final String TOKEN_HEADER = "X-CSRF-Token";

    /** Cookie double-submit pose par la passerelle, relaye tel quel au service. */
    public static final String TOKEN_COOKIE = "csrf-token";

    /** Longueur maximale acceptee, reprise de la source (jeton > 100 caracteres refuse). */
    public static final int MAX_TOKEN_LENGTH = 100;

    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final RestResponse<Void> FORBIDDEN = RestResponse.status(403);

    @ServerRequestFilter(preMatching = true)
    public Optional<RestResponse<Void>> enforceDoubleSubmit(ContainerRequestContext ctx) {
        if (!UNSAFE_METHODS.contains(ctx.getMethod())) {
            return Optional.empty();
        }
        String headerToken = ctx.getHeaderString(TOKEN_HEADER);
        String cookieToken = forwardedCsrfCookie(ctx);
        if (headerToken == null || cookieToken == null
                || headerToken.length() > MAX_TOKEN_LENGTH) {
            return Optional.of(FORBIDDEN);
        }
        return MessageDigest.isEqual(bytes(headerToken), bytes(cookieToken))
                ? Optional.empty()
                : Optional.of(FORBIDDEN);
    }

    private static String forwardedCsrfCookie(ContainerRequestContext ctx) {
        Cookie cookie = ctx.getCookies().get(TOKEN_COOKIE);
        return cookie == null ? null : cookie.getValue();
    }

    private static byte[] bytes(String token) {
        return token.getBytes(StandardCharsets.UTF_8);
    }
}
