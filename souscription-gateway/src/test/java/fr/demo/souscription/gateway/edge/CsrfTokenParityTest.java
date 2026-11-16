package fr.demo.souscription.gateway.edge;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.gateway.runtime.GatewayConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Parity skeleton for 15-souscription-gateway-csrf-token-service.
 *
 * Three properties of {@code fr.demo.souscription.web.SecurityFilter} must survive exactly:
 * the {@code MessageDigest.isEqual} comparison on UTF-8 bytes (FG-002), the rejection of a
 * submitted token longer than 100 characters, and the fact that an existing token is never
 * re-issued. Two are deliberately strengthened: the token is 32 CSPRNG bytes instead of a random
 * UUID, and the session-bound lifetime becomes an explicit TTL verified server side (the cookie
 * {@code Max-Age} is a client-side hint only).
 *
 * A {@code String.equals} substitution, a relaxed bound, or a {@code Max-Age}-only expiry would
 * each be a security regression that functional tests alone do not reveal.
 */
@QuarkusTest
class CsrfTokenParityTest {

    private static final Path SERVICE_SOURCE = Path.of("src", "main", "java", "fr", "demo",
            "souscription", "gateway", "edge", "CsrfTokenService.java");
    private static final Path ISSUER_SOURCE = Path.of("src", "main", "java", "fr", "demo",
            "souscription", "gateway", "edge", "CsrfCookieIssuer.java");
    private static final Path PROPERTIES = Path.of("src", "main", "resources",
            "application.properties");

    @Inject
    CsrfTokenService tokens;

    @Inject
    GatewayConfig config;

    /** Step 1: preserved comparison semantics and preserved bound. */
    @Test
    void matchingPairAcceptedDifferingPairRejectedOversizedRejected() {
        String token = tokens.issue();
        assertTrue(tokens.matches(token, token), "a matching double-submit pair must be accepted");
        assertFalse(tokens.matches(token, tokens.issue()), "a differing pair must be rejected");
        assertFalse(tokens.matches(token, null));
        assertFalse(tokens.matches(null, token));

        int bound = config.csrf().maxTokenLength();
        assertEquals(100, bound, "the 100-character bound of SecurityFilter must be preserved");
        String oversized = "x".repeat(bound + 1);
        assertFalse(tokens.matches(oversized, oversized),
                "a header longer than the bound must be rejected before any comparison");
    }

    /** Step 2: the constant-time comparison is still the one used. */
    @Test
    void constantTimeComparisonIsStillUsed() throws Exception {
        String source = Files.readString(SERVICE_SOURCE, StandardCharsets.UTF_8);
        assertTrue(source.contains("MessageDigest.isEqual"),
                "the double-submit comparison must use MessageDigest.isEqual");
        String code = source.substring(source.indexOf("public String issue()"));
        assertFalse(code.contains(".equals("),
                "no equals() on a token value: it leaks the common prefix length (FG-002)");
    }

    /** Step 3: entropy strengthening, no regression back to a UUID. */
    @Test
    void tokenCarries32CsprngBytesAndIsUniqueAcrossIssuances() throws Exception {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = tokens.issue();
            assertTrue(seen.add(token), "two issued tokens collided: " + token);
            assertTrue(token.length() <= config.csrf().maxTokenLength(),
                    "an issued token must stay inside the preserved bound: " + token.length());
            byte[] raw = Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.')));
            assertEquals(32, raw.length, "the random part must be 32 bytes (>= 128 bits)");
        }
        String source = Files.readString(SERVICE_SOURCE, StandardCharsets.UTF_8);
        assertTrue(source.contains("SecureRandom"), "the token must come from SecureRandom");
        assertFalse(source.contains("UUID.randomUUID"),
                "the UUID token of the source must not come back: the strengthening is deliberate");
        assertFalse(source.contains("ThreadLocalRandom") || source.contains("new Random("),
                "a non-cryptographic generator defeats the control");
    }

    /** Step 4: expiry verified server side, not by the cookie Max-Age alone. */
    @Test
    void expiryIsVerifiedServerSide() {
        assertEquals(Duration.ofMinutes(20), tokens.ttl(),
                "the TTL must mirror the former 20-minute session window");

        CsrfTokenService shortLived = new CsrfTokenService(new ShortTtlConfig(config));
        String fresh = shortLived.issue();
        assertFalse(shortLived.isExpired(fresh), "a freshly issued token must not be expired");
        assertTrue(shortLived.isExpired(stale(shortLived)),
                "a token older than the configured TTL must be reported as expired server side");
        assertTrue(shortLived.isExpired(fresh.substring(0, fresh.lastIndexOf('.') + 1) + "AAAA"),
                "the HMAC must be verified, otherwise a client extends its own token");
        assertTrue(shortLived.isExpired("not-a-token"));
        assertTrue(shortLived.isExpired(null));

        Response replaced = given().cookie(config.csrf().cookieName(), "jeton-invalide")
                .when().get("/api/v1/csrf-token");
        assertNotEquals("jeton-invalide", replaced.jsonPath().getString("token"),
                "a token that does not verify must be refused server side and replaced");
    }

    /** Step 5: cookie attributes of the double-submit scheme. */
    @Test
    void cookieIsSecureStrictRootScopedAndReadableBySpa() {
        Response response = given().when().get("/api/v1/csrf-token");
        response.then().statusCode(200);
        String setCookie = csrfSetCookie(response);
        String lower = setCookie.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("secure"), "cookie must be Secure: " + setCookie);
        assertTrue(lower.contains("samesite=strict"),
                "cookie must be SameSite=Strict (OD-004, same origin): " + setCookie);
        assertTrue(lower.contains("path=/"), "cookie must be scoped to Path=/: " + setCookie);
        assertFalse(lower.contains("httponly"),
                "the SPA must read the cookie to echo it: " + setCookie);
        assertEquals(response.jsonPath().getString("token"), cookieValue(setCookie),
                "cookie and body must carry the same token (double submit)");
    }

    /** Step 6: idempotent, lock-free issuance. */
    @Test
    void existingTokenIsNotReissuedAndNoLockGuardsIssuance() throws Exception {
        List<String> firstCall = given().when().get("/api/v1/products")
                .getHeaders().getValues("Set-Cookie");
        assertTrue(firstCall.stream().anyMatch(this::isCsrfCookie),
                "a safe request without cookie must receive one: " + firstCall);

        String existing = tokens.issue();
        List<String> secondCall = given().cookie(config.csrf().cookieName(), existing)
                .when().get("/api/v1/products")
                .getHeaders().getValues("Set-Cookie");
        assertTrue(secondCall.stream().noneMatch(this::isCsrfCookie),
                "the existing cookie must be preserved, as the session token was: " + secondCall);

        assertFalse(Files.readString(ISSUER_SOURCE, StandardCharsets.UTF_8)
                        .replace("{@code synchronized}", "")
                        .contains("synchronized"),
                "the source's synchronized(session) must not come back (FG-003, AG-015)");
    }

    /** Step 7: the signing key has no default value. */
    @Test
    void signingKeyHasNoDefaultValue() throws Exception {
        String properties = Files.readString(PROPERTIES, StandardCharsets.UTF_8);
        assertTrue(properties.contains("gateway.csrf.signing-key=${CSRF_SIGNING_KEY}"),
                "the signing key must be read from the environment without a default");
        assertFalse(properties.contains("CSRF_SIGNING_KEY:"),
                "a default secret in the repository makes every deployment forgeable");
        assertTrue(properties.contains("gateway.csrf.max-token-length=${CSRF_MAX_LEN:100}"),
                "the preserved bound must stay configured at 100");
    }

    /** Step 8: the token endpoint is part of the published contract. */
    @Test
    void tokenEndpointIsPublishedInTheOpenApiDocument() {
        int managementPort = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("quarkus.management.test-port", Integer.class).orElse(9001);
        String document = given().port(managementPort).accept("application/json")
                .when().get("/q/openapi").then().statusCode(200)
                .extract().body().asString();
        assertTrue(document.contains("/api/v1/csrf-token"),
                "GET /api/v1/csrf-token must appear in the published contract");
        assertTrue(document.contains("issueCsrfToken"),
                "the operation id of the provider contract must be published");
    }

    private boolean isCsrfCookie(String header) {
        return header.startsWith(config.csrf().cookieName() + "=");
    }

    private String csrfSetCookie(Response response) {
        return response.getHeaders().getValues("Set-Cookie").stream()
                .filter(this::isCsrfCookie)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CSRF cookie issued"));
    }

    private static String cookieValue(String setCookie) {
        String first = setCookie.split(";", 2)[0];
        return first.substring(first.indexOf('=') + 1);
    }

    /** A token signed by the service but dated before its TTL window. */
    private static String stale(CsrfTokenService service) {
        String fresh = service.issue();
        String payload = fresh.substring(0, fresh.indexOf('.')) + "."
                + Instant.now().minus(Duration.ofHours(1)).getEpochSecond();
        return payload + "." + signatureOf(service, payload);
    }

    private static String signatureOf(CsrfTokenService service, String payload) {
        try {
            var sign = CsrfTokenService.class.getDeclaredMethod("sign", String.class);
            sign.setAccessible(true);
            return (String) sign.invoke(service, payload);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("sign(String) is expected on CsrfTokenService", e);
        }
    }

    /** {@link GatewayConfig} view with a one-second token lifetime, for the expiry assertions. */
    private record ShortTtlConfig(GatewayConfig delegate) implements GatewayConfig {

        @Override
        public Csrf csrf() {
            Csrf origin = delegate.csrf();
            return new Csrf() {
                @Override
                public String cookieName() {
                    return origin.cookieName();
                }

                @Override
                public String headerName() {
                    return origin.headerName();
                }

                @Override
                public int maxTokenLength() {
                    return origin.maxTokenLength();
                }

                @Override
                public Duration ttl() {
                    return Duration.ofSeconds(1);
                }

                @Override
                public String signingKey() {
                    return origin.signingKey();
                }
            };
        }

        @Override
        public Security security() {
            return delegate.security();
        }
    }
}
