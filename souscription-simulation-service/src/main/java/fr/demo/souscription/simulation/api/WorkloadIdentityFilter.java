package fr.demo.souscription.simulation.api;

import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Workload-identity allow-list for the mTLS transport (W6-T01).
 *
 * <p>The TLS layer already rejects a caller that presents no certificate or one the cluster PKI does
 * not trust ({@code quarkus.http.ssl.client-auth=required}); that failure happens before any HTTP
 * request exists and is therefore not observable here. What the PKI cannot express is <em>which</em>
 * of its workloads may call this service: it issues a certificate to every pod of the cluster, so
 * trusting the CA alone would authorise all of them. This filter closes that gap by requiring the
 * peer certificate to carry one of the SPIFFE identities frozen in the two security policies
 * ({@code acceptedSubjects} / {@code allowedCallerIdentities}): souscription-gateway and
 * souscription-service. Every other PKI-valid identity gets 403.
 *
 * <p>The identity is read from the certificate's {@code subjectAltName} URI entries only - never
 * from a header, which a caller controls, and never from the CN, which is not an identity in the
 * SPIFFE model. The allow-list itself is configuration
 * ({@code simulation.mtls.accepted-identities}), so a change of contract is a redeploy, not a code
 * change.
 *
 * <p>This filter deliberately owns authentication of the <em>workload</em> and nothing else: the
 * browser-facing request forgery control, the HTTP-method allow-list and the security response
 * headers stay at souscription-gateway, and no end-user authentication exists anywhere in the
 * system (none exists in the source).
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class WorkloadIdentityFilter implements ContainerRequestFilter {

    /**
     * Request property carrying the contracted identity of the caller, consumed by
     * {@link RateLimitFilter} to scope the rate limit per calling identity without parsing the
     * certificate a second time.
     */
    static final String CALLER_IDENTITY = "simulation.caller.identity";

    static final String TITLE = "Appelant non autorisé";
    static final String DETAIL = "L'identité du workload appelant ne figure pas dans la liste "
            + "des appelants contractualisés.";

    /** subjectAltName general-name type 6: uniformResourceIdentifier (RFC 5280). */
    private static final int SAN_URI = 6;

    private static final Logger LOG = Logger.getLogger(WorkloadIdentityFilter.class);

    @Inject
    RoutingContext routingContext;

    @Context
    HttpHeaders headers;

    @ConfigProperty(name = "simulation.mtls.accepted-identities")
    Set<String> acceptedIdentities;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        List<String> presented = peerIdentities();
        String contracted = presented.stream()
                .filter(acceptedIdentities::contains)
                .findFirst()
                .orElse(null);
        if (contracted == null) {
            // The rejected identity is logged, not returned: the body must not tell an unlisted
            // caller which identities would have been accepted.
            LOG.warnf("Rejected call from an uncontracted workload identity %s", presented);
            requestContext.abortWith(ProblemJson.response(403, TITLE, DETAIL, null, headers));
            return;
        }
        requestContext.setProperty(CALLER_IDENTITY, contracted);
    }

    /** SPIFFE identities carried by the peer certificate; empty when the peer presented none. */
    private List<String> peerIdentities() {
        SSLSession session = routingContext.request().sslSession();
        if (session == null) {
            return List.of();
        }
        Certificate[] chain;
        try {
            chain = session.getPeerCertificates();
        } catch (SSLPeerUnverifiedException e) {
            return List.of();
        }
        if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
            return List.of();
        }
        try {
            Collection<List<?>> names = leaf.getSubjectAlternativeNames();
            if (names == null) {
                return List.of();
            }
            return names.stream()
                    .filter(name -> name.size() == 2 && SAN_URI == (Integer) name.get(0))
                    .map(name -> String.valueOf(name.get(1)))
                    .toList();
        } catch (CertificateParsingException e) {
            return List.of();
        }
    }
}
