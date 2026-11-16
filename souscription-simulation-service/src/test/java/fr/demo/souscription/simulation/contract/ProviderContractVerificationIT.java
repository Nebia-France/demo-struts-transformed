package fr.demo.souscription.simulation.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion.VersionFlag;
import com.networknt.schema.ValidationMessage;

import fr.demo.souscription.simulation.api.SimulationRequest;
import fr.demo.souscription.simulation.api.SimulationResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider-side contract verification of the two frozen contracts this application provides
 * (plan 32). It changes no production code and asserts nothing new: it checks that what is
 * published still matches what is implemented, on the three axes the pipeline cannot read from the
 * OpenAPI document alone — live schema conformance, resilience values and transport security — plus
 * the role assertion that there is <em>no consumed contract</em> to stub.
 *
 * <p>The fourth axis, OpenAPI equivalence, is the {@code contract-suite} stage's diff of
 * {@code target/openapi.yaml} against both frozen documents
 * ({@code .ci/templates/openapi-contract-diff.py}); it is not duplicated here.
 *
 * <p>Every contracted figure is <strong>read from the frozen file</strong> rather than restated, so
 * a contract edit cannot pass unnoticed. The behavioural halves of the resilience axis (503 on
 * bulkhead overflow, 429 on rate-limit excess, the server timeout answering inside 2 500 ms) are
 * owned by {@code FaultInjectionTest}, which carries the same {@code contract} tag.
 *
 * <p>Carries {@code @Tag("contract")}: that is how a test joins the single reusable contract-test
 * suite the provider pipeline and both consumer pipelines run.
 */
@QuarkusTest
@Tag("contract")
class ProviderContractVerificationIT {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String VALID_BODY =
            "{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                    + "\"durationYears\":5,\"annualRate\":\"0.040000\"}";
    private static final String INVALID_BODY =
            "{\"initialAmount\":\"99.99\",\"monthlyAmount\":\"100.00\","
                    + "\"durationYears\":5,\"annualRate\":\"0.040000\"}";

    // ------------------------------------------------------------------ roles

    /**
     * Exactly two communications target this application and none originates from it. The "no stub
     * required" premise of this plan is exactly that second half: an outbound business call would
     * make this service's readiness depend on another one, and would require a consumer-side stub
     * that does not exist.
     */
    @Test
    void providesTwoContractsAndConsumesNone() {
        JsonNode contracts = FrozenContracts.registry().get("contracts");
        List<String> provided = StreamSupport.stream(contracts.spliterator(), false)
                .filter(c -> FrozenContracts.APPLICATION.equals(c.get("to").asText()))
                .map(c -> c.get("communicationId").asText())
                .sorted()
                .toList();
        List<String> consumed = StreamSupport.stream(contracts.spliterator(), false)
                .filter(c -> FrozenContracts.APPLICATION.equals(c.get("from").asText()))
                .map(c -> c.get("communicationId").asText())
                .toList();

        assertEquals(List.of(FrozenContracts.GATEWAY_CONTRACT, FrozenContracts.SERVICE_CONTRACT),
                provided,
                "the provider role must cover exactly the gateway and souscription-service contracts");
        assertEquals(List.of(), consumed,
                "a consumed contract appeared: this service must have no outbound business call,"
                        + " and the missing stub plan must be raised before cut-over");
    }

    // ----------------------------------------------------------- live schemas

    /**
     * A live 200 body validates against {@code shared-schemas/SimulationResult.json}, including the
     * {@code ^[0-9]{1,6}\.[0-9]{2}$} pattern on {@code contributed} and {@code finalCapital}.
     *
     * <p>The <strong>raw</strong> body is validated: a validator fed a re-serialised object would
     * accept a representation the consumer never receives (a collapsed scale, a {@code 2.4E+3}
     * normalisation, a number where the contract says string).
     */
    @Test
    void liveSuccessBodyValidatesAgainstTheSharedSchema() {
        Response response = given().contentType(MediaType.APPLICATION_JSON).body(VALID_BODY)
                .when().post("/v1/simulations")
                .thenReturn();

        assertEquals(200, response.statusCode(), response.asString());
        assertEquals(MediaType.APPLICATION_JSON, response.getContentType().split(";")[0].trim());
        String raw = response.asString();
        assertNoSchemaViolation("SimulationResult.json", raw);
    }

    /**
     * A live 400 body validates against {@code shared-schemas/FieldErrors.json}, with the
     * {@code problem+json} media type, the wire field names and the French message byte-identical to
     * the legacy one.
     */
    @Test
    void liveValidationFailureBodyValidatesAgainstTheSharedSchema() {
        Response response = given().contentType(MediaType.APPLICATION_JSON).body(INVALID_BODY)
                .when().post("/v1/simulations")
                .thenReturn();

        assertEquals(400, response.statusCode(), response.asString());
        assertEquals(PROBLEM_JSON, response.getContentType().split(";")[0].trim());
        String raw = response.asString();
        assertNoSchemaViolation("FieldErrors.json", raw);

        JsonNode body = FrozenContracts.parse(raw);
        assertEquals("initialAmount", body.get("errors").get(0).get("field").asText());
        assertEquals("Le versement initial doit être compris entre 100,00 et 100 000,00 €."
                        + " Utilisez au maximum deux décimales, sans séparateur de milliers.",
                body.get("errors").get(0).get("message").asText(),
                "the French message is displayed to a user: it must stay byte-identical");
        // 4xx carries no retry allowance (retry.nonRetryableConditions of both contracts), so
        // nothing in the response may invite a retry.
        assertNull(response.getHeader("Retry-After"),
                "a 4xx is never retryable: no Retry-After may be sent");
    }

    private static void assertNoSchemaViolation(String schemaName, String rawBody) {
        Set<ValidationMessage> violations = validate(schemaName, rawBody);
        assertTrue(violations.isEmpty(),
                "live body does not satisfy global/contracts/shared-schemas/" + schemaName + ": "
                        + violations + " body: " + rawBody);
    }

    private static Set<ValidationMessage> validate(String schemaName, String rawBody) {
        JsonSchema schema = JsonSchemaFactory.getInstance(VersionFlag.V202012)
                .getSchema(FrozenContracts.sharedSchema(schemaName));
        return schema.validate(FrozenContracts.parse(rawBody));
    }

    /**
     * Guards the two checks above against being vacuous: a validator that accepts everything —
     * because a {@code $ref} silently failed to resolve, say — would turn both into green
     * no-ops. A scale-1 amount and a numeric amount are exactly what the consumers must never
     * receive, so both must be rejected by the frozen schema as loaded here.
     */
    @Test
    void theSharedSchemaActuallyRejectsANonConformingBody() {
        String scaleOne = "{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\","
                + "\"durationYears\":5,\"contributed\":\"7000.00\",\"finalCapital\":\"7350.0\"}";
        String numeric = "{\"initialAmount\":1000.00,\"monthlyAmount\":\"100.00\","
                + "\"durationYears\":5,\"contributed\":\"7000.00\",\"finalCapital\":\"7350.00\"}";

        assertFalse(validate("SimulationResult.json", scaleOne).isEmpty(),
                "a scale-1 finalCapital must breach the contract pattern");
        assertFalse(validate("SimulationResult.json", numeric).isEmpty(),
                "a numeric amount must breach the contract type");
        assertFalse(validate("FieldErrors.json", "{\"title\":\"x\",\"status\":400}").isEmpty(),
                "a problem document without `type` must breach the contract required list");
    }

    // -------------------------------------------------------- resilience axis

    /**
     * The implemented capacity and timeout match both frozen resilience contracts: the server
     * timeout is strictly below the caller timeout, and the bulkhead is the stricter of the two
     * declared concurrency figures.
     *
     * <p>The gateway contract's {@code maxConcurrentCalls = 30} is a caller-side allowance, not a
     * server capacity promise (BASELINE.md §5): it is asserted as an upper bound on the implemented
     * server-side limit, never reconciled by editing either file.
     */
    @ParameterizedTest
    @ValueSource(strings = {FrozenContracts.GATEWAY_CONTRACT, FrozenContracts.SERVICE_CONTRACT})
    void implementedResilienceMatchesTheFrozenContract(String communicationId) throws Exception {
        JsonNode contract = FrozenContracts.resilience(communicationId);
        Method compute = SimulationResource.class.getMethod(
                "compute", SimulationRequest.class, String.class, String.class);

        Timeout timeout = compute.getAnnotation(Timeout.class);
        Bulkhead bulkhead = compute.getAnnotation(Bulkhead.class);
        assertEquals(ChronoUnit.MILLIS, timeout.unit());
        long callerTimeoutMillis = millis(contract.get("timeout").get("value").asText());
        assertTrue(timeout.value() < callerTimeoutMillis,
                "the server timeout (" + timeout.value() + " ms) must stay strictly below the "
                        + callerTimeoutMillis + " ms caller timeout of " + communicationId);
        assertEquals(2500L, timeout.value(), "published server timeout (CONTRACT.md)");
        assertEquals(20, bulkhead.value(), "server-side bulkhead, the stricter of the two contracts");
        assertEquals(40, bulkhead.waitingTaskQueue(), "bounded waiting queue (CONTRACT.md)");

        int declaredConcurrency = contract.get("bulkhead").get("maxConcurrentCalls").asInt();
        assertTrue(bulkhead.value() <= declaredConcurrency,
                "the implemented capacity must never promise more than " + communicationId
                        + " declares (" + declaredConcurrency + ")");

        List<String> nonRetryable = texts(contract.get("retry").get("nonRetryableConditions"));
        assertTrue(nonRetryable.contains("any 4xx response") && nonRetryable.contains("HTTP 429"),
                "4xx and 429 must stay non-retryable in " + communicationId + ": " + nonRetryable);
        assertTrue(texts(contract.get("retry").get("retryableConditions")).contains("HTTP 503"),
                "503 is the overload verdict and must stay retryable once in " + communicationId);
    }

    // ---------------------------------------------------------- security axis

    /**
     * The implemented transport matches both frozen security policies: TLS 1.3 only, client
     * authentication required with no plaintext listener, and only the contractual workload
     * identities accepted.
     */
    @ParameterizedTest
    @ValueSource(strings = {FrozenContracts.GATEWAY_CONTRACT, FrozenContracts.SERVICE_CONTRACT})
    void implementedSecurityMatchesTheFrozenPolicy(String communicationId) {
        JsonNode policy = FrozenContracts.security(communicationId);
        JsonNode tls = policy.get("tls");

        assertEquals("TLSv1." + tls.get("minimumVersion").asText().split("\\.")[1],
                config("quarkus.http.ssl.protocols"),
                "only the contracted TLS version may be offered");
        assertEquals(texts(tls.get("cipherSuites")),
                List.of(config("quarkus.http.ssl.cipher-suites").split(",")),
                "cipher suites are the contracted ones, in the contracted order");
        assertTrue(tls.get("mutual").asBoolean());
        assertEquals("required", config("quarkus.http.ssl.client-auth"),
                "an unauthenticated caller must fail in the handshake, never with a 401/403");
        assertEquals("disabled", config("quarkus.http.insecure-requests"),
                "no plaintext listener may exist to downgrade to");
        assertFalse(Boolean.parseBoolean(config("quarkus.http.cors")),
                "no browser origin is involved in a cluster-internal call");

        List<String> accepted = List.of(config("simulation.mtls.accepted-identities").split(","));
        for (String subject : texts(policy.get("authentication").get("details").get("acceptedSubjects"))) {
            assertTrue(accepted.contains(subject),
                    "contracted identity " + subject + " is not accepted: " + accepted);
        }
        assertEquals(2, accepted.size(),
                "only the two contractual workload identities may be accepted: " + accepted);
        assertTrue(policy.get("authentication").get("details").get("rejectAllOtherIdentities").asBoolean());

        // Rate limiting is part of the same policy: the configured ceiling is the contracted one.
        JsonNode rateLimiting = policy.get("rateLimiting");
        assertEquals(rateLimiting.get("requestsPerSecond").asInt(),
                Integer.parseInt(defaultOf("simulation.ratelimit.requests-per-second", "100")));
        assertEquals(rateLimiting.get("burstSize").asInt(),
                Integer.parseInt(defaultOf("simulation.ratelimit.burst", "200")));

        assertFalse(policy.get("network").get("publiclyReachable").asBoolean());
    }

    /**
     * No external route exists: the rendered manifests expose a {@code ClusterIP} Service and
     * neither an Ingress nor a Route, so the two statements of the policies' {@code network} block
     * are checked against the manifests rather than taken on trust.
     */
    @Test
    void noExternalRouteIsDeclared() throws IOException {
        // The network objects are rendered from the shared template; the module owns parameters
        // only, so both halves are checked: the emitted kinds and this module's own instance.
        String network = Files.readString(
                FrozenContracts.path(".ci/templates/deploy/quarkus-service-network.yml"));
        assertTrue(network.contains("type: ClusterIP"), "the Service stays ClusterIP");
        assertFalse(network.contains("kind: Ingress") || network.contains("kind: Route"),
                "the three backend services must be unreachable from outside the cluster");

        String instance = Files.readString(
                FrozenContracts.path("souscription-simulation-service/deploy/networkpolicy.yml"));
        assertFalse(instance.contains("kind: Ingress") || instance.contains("kind: Route"),
                "no external route may be introduced by the module's own parameters");
    }

    // ---------------------------------------------------------------- helpers

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }

    /**
     * The contracted default of an externalised key, read from the module's own
     * {@code application.properties}: the running value is deliberately raised in the test profile
     * so the suites are not throttled, and it is the default that must carry the contract figure.
     */
    private static String defaultOf(String key, String fallback) throws AssertionError {
        try {
            String properties = Files.readString(FrozenContracts.path(
                    "souscription-simulation-service/src/main/resources/application.properties"));
            for (String line : properties.lines().toList()) {
                if (line.startsWith(key + "=")) {
                    String value = line.substring(key.length() + 1);
                    int colon = value.indexOf(':');
                    return colon < 0 ? value : value.substring(colon + 1).replace("}", "");
                }
            }
        } catch (IOException e) {
            throw new AssertionError("unreadable application.properties", e);
        }
        throw new AssertionError("no configuration key " + key + " (expected default " + fallback + ")");
    }

    private static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    private static long millis(String duration) {
        if (duration.endsWith("ms")) {
            return Long.parseLong(duration.substring(0, duration.length() - 2));
        }
        return Long.parseLong(duration.substring(0, duration.length() - 1)) * 1000L;
    }
}
