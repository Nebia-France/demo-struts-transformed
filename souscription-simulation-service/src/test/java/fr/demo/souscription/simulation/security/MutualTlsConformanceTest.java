package fr.demo.souscription.simulation.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import javax.net.ssl.SSLException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Conformance of the mTLS transport and of the workload-identity allow-list (W6-T01).
 *
 * <p>The three verdicts the plan requires are asserted at the level where they are actually
 * enforced, which is not the same level for all three:
 * <ul>
 *   <li>no client certificate - rejected by TLS, before any HTTP request exists, so the assertion is
 *       on a handshake failure and never on a 401/403 status;</li>
 *   <li>a PKI-valid but uncontracted identity - accepted by TLS and rejected by the application with
 *       403 problem+json, since the PKI cannot express which of its workloads may call;</li>
 *   <li>a contracted identity - served normally.</li>
 * </ul>
 */
@QuarkusTest
@Tag("contract")
class MutualTlsConformanceTest {

    private static final String VALID_BODY =
            "{\"initialAmount\":1000.00,\"monthlyAmount\":100.00,\"durationYears\":5,"
                    + "\"annualRate\":0.04}";

    @ConfigProperty(name = "simulation.mtls.accepted-identities")
    Set<String> acceptedIdentities;

    /** The allow-list is the one frozen in the two security policies, not a wider one. */
    @Test
    void onlyTheTwoContractedWorkloadsAreAccepted() {
        assertEquals(Set.of(MutualTlsHarness.GATEWAY_IDENTITY,
                        "spiffe://horizon-souscription/ns/horizon/sa/souscription-service"),
                acceptedIdentities,
                "the accepted identities must be exactly the gateway and souscription-service");
        assertFalse(acceptedIdentities.contains(MutualTlsHarness.ROGUE_IDENTITY),
                "souscription-web calls through the gateway and is never a direct caller");
    }

    /** A contracted caller is served. */
    @Test
    void theContractedGatewayIdentityIsServed() {
        RestAssured.given().contentType(ContentType.JSON).body(VALID_BODY)
                .when().post("/v1/simulations")
                .then().statusCode(200);
    }

    /**
     * No client certificate: the connection must die in the handshake. An application-level 401 or
     * 403 here would mean the request was accepted onto an unauthenticated connection.
     */
    @Test
    void aCallWithoutAClientCertificateFailsAtTheTlsLayer() throws Exception {
        HttpClient trustOnly = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .sslContext(MutualTlsHarness.serverTrustOnlyContext())
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MutualTlsHarness.trafficBaseUri() + "/v1/simulations"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(VALID_BODY))
                .build();

        IOException failure = assertThrows(IOException.class,
                () -> trustOnly.send(request, HttpResponse.BodyHandlers.ofString()),
                "a certificate-less client must not obtain an HTTP response at all");
        assertTrue(failure instanceof SSLException
                        || failure.getCause() instanceof SSLException
                        || failure.getMessage().contains("certificate"),
                "the rejection must come from the TLS layer, got: " + failure);
    }

    /** A PKI-valid identity that is not in the contracts is rejected by the allow-list. */
    @Test
    void anUnlistedWorkloadIdentityIsRejectedWith403() {
        Response response = MutualTlsHarness.rogueIdentity()
                .contentType(ContentType.JSON).body(VALID_BODY)
                .when().post("/v1/simulations")
                .then().extract().response();

        assertEquals(403, response.statusCode(),
                "an uncontracted workload must be refused, got: " + response.asString());
        assertTrue(response.contentType().startsWith("application/problem+json"),
                "the refusal must be problem+json, was " + response.contentType());
        assertFalse(response.asString().contains("spiffe://"),
                "the body must not disclose which identities would be accepted: "
                        + response.asString());
    }
}
