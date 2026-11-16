package fr.demo.souscription.gateway.api;

import fr.demo.souscription.gateway.api.dto.CsrfTokenView;
import fr.demo.souscription.gateway.edge.CsrfCookieIssuer;
import fr.demo.souscription.gateway.edge.CsrfTokenService;
import fr.demo.souscription.gateway.error.Problem;
import fr.demo.souscription.gateway.runtime.GatewayConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * CSRF token endpoint of the provider contract ({@code GET /api/v1/csrf-token}, W3-T02).
 *
 * <p>Returns the double-submit token in the body and sets it in the {@code XSRF-TOKEN} cookie. A
 * live cookie is returned as is rather than replaced, which keeps the endpoint idempotent and
 * preserves the behaviour of the source filter: an existing session token was never regenerated.
 */
@Path("/api/v1/csrf-token")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "security", description = "CSRF token issuance at the edge.")
public class CsrfTokenResource {

    private final CsrfTokenService tokens;
    private final CsrfCookieIssuer cookies;
    private final GatewayConfig config;

    @Inject
    public CsrfTokenResource(CsrfTokenService tokens, CsrfCookieIssuer cookies,
            GatewayConfig config) {
        this.tokens = tokens;
        this.cookies = cookies;
        this.config = config;
    }

    @GET
    @Operation(operationId = "issueCsrfToken",
            summary = "Issue a CSRF token for the double-submit cookie scheme")
    @APIResponse(responseCode = "200", description = "Token issued.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = CsrfTokenView.class)))
    @APIResponse(responseCode = "405", description = "Methode HTTP non autorisee (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "500", description = "Erreur interne (RFC 7807)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = Problem.class)))
    public Response issue(@Context HttpHeaders headers) {
        var present = headers.getCookies().get(config.csrf().cookieName());
        String live = cookies.liveToken(present == null ? null : present.getValue());
        String token = live != null ? live : tokens.issue();
        return Response.ok(new CsrfTokenView(token))
                .cookie(cookies.newCookie(token))
                .build();
    }
}
