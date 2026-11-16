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
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * W3-T02: the stateless CSRF token must be at least as strong as the session token of
 * {@code fr.demo.souscription.web.SecurityFilter}.
 *
 * <p>Asserts the two preserved properties (constant-time comparison, 100-character bound), the
 * strengthened entropy, the bounded lifetime and the cookie attributes required by the provider
 * contract.
 */
@QuarkusTest
class CsrfTokenServiceTest {

    private static final Path SERVICE_SOURCE = Path.of("src", "main", "java", "fr", "demo",
            "souscription", "gateway", "edge", "CsrfTokenService.java");

    @Inject
    CsrfTokenService tokens;

    @Inject
    GatewayConfig config;

    @Test
    void issuedTokenCarries32BytesOfRandomnessAndStaysInsideTheBound() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = tokens.issue();
            assertTrue(seen.add(token), "two issued tokens collided: " + token);
            assertTrue(token.length() <= config.csrf().maxTokenLength(),
                    "token longer than the preserved bound: " + token.length());
            String random = token.substring(0, token.indexOf('.'));
            byte[] raw = Base64.getUrlDecoder().decode(random);
            assertEquals(32, raw.length, "the random part must be 32 bytes (256 bits)");
            assertFalse(tokens.isExpired(token), "a freshly issued token must not be expired");
        }
    }

    @Test
    void comparisonIsConstantTimeAndNotStringEquals() throws Exception {
        String source = Files.readString(SERVICE_SOURCE, StandardCharsets.UTF_8);
        assertTrue(source.contains("MessageDigest.isEqual"),
                "the double-submit comparison must use MessageDigest.isEqual");
        String body = source.substring(source.indexOf("public String issue()"));
        assertFalse(body.contains(".equals("),
                "no String.equals on token values: it leaks the common prefix length (FG-002)");
    }

    @Test
    void matchingAcceptsIdenticalValuesAndRejectsEverythingElse() {
        String token = tokens.issue();
        assertTrue(tokens.matches(token, token));
        assertFalse(tokens.matches(token, null));
        assertFalse(tokens.matches(null, token));
        assertFalse(tokens.matches(token, token.substring(0, token.length() - 1)));
        assertFalse(tokens.matches(token, tokens.issue()));
    }

    @Test
    void submittedTokenAboveTheConfiguredBoundIsRejectedBeforeComparison() {
        int bound = config.csrf().maxTokenLength();
        assertEquals(100, bound, "the bound of the source filter must be preserved");
        String oversized = "x".repeat(bound + 1);
        assertFalse(tokens.matches(oversized, oversized),
                "a token longer than the bound must be rejected even when it matches the cookie");
    }

    @Test
    void lifetimeIsBoundedAndVerifiedServerSide() {
        assertEquals(Duration.ofMinutes(20), tokens.ttl(),
                "the token lifetime must mirror the former 20-minute session window");

        CsrfTokenService shortLived = new CsrfTokenService(new ShortTtlConfig(config));
        String token = shortLived.issue();
        assertFalse(shortLived.isExpired(token));
        // The issue timestamp is in the token, so an older one can be forged only by re-signing it:
        // a token whose signature does not verify is reported as expired.
        assertTrue(shortLived.isExpired(token.substring(0, token.lastIndexOf('.') + 1) + "AAAA"),
                "a token with a broken signature must not be trusted");
        assertTrue(shortLived.isExpired("not-a-token"));
        assertTrue(shortLived.isExpired(null));
        assertTrue(shortLived.isExpired(stale(shortLived)),
                "a token issued before the TTL must be detected as expired server side");
    }

    @Test
    void theTokenEndpointSetsAReadableStrictlyScopedSecureCookie() {
        Response response = given().when().get("/api/v1/csrf-token");
        response.then().statusCode(200);
        String token = response.jsonPath().getString("token");
        assertTrue(token != null && token.length() >= 32, "no token in the body: " + token);

        String setCookie = response.getHeaders().getValues("Set-Cookie").stream()
                .filter(h -> h.startsWith(config.csrf().cookieName() + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CSRF cookie issued"));
        String lower = setCookie.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("secure"), "cookie must be Secure: " + setCookie);
        assertTrue(lower.contains("samesite=strict"),
                "cookie must be SameSite=Strict: " + setCookie);
        assertTrue(lower.contains("path=/"), "cookie must be scoped to Path=/: " + setCookie);
        assertFalse(lower.contains("httponly"),
                "the cookie must stay readable by the SPA: " + setCookie);
        assertTrue(lower.contains("max-age=" + tokens.ttl().toSeconds()),
                "cookie Max-Age must mirror gateway.csrf.ttl: " + setCookie);
        assertEquals(token, cookieValue(setCookie),
                "the cookie and the body must carry the same token (double submit)");
    }

    @Test
    void aLiveCookieIsNotReissuedButAnExpiredOneIs() {
        String existing = tokens.issue();
        Response kept = given().cookie(config.csrf().cookieName(), existing)
                .when().get("/api/v1/csrf-token");
        assertEquals(existing, kept.jsonPath().getString("token"),
                "a live token must be preserved, as the session token was");

        Response replaced = given().cookie(config.csrf().cookieName(), "jeton-invalide")
                .when().get("/api/v1/csrf-token");
        assertNotEquals("jeton-invalide", replaced.jsonPath().getString("token"),
                "an unverifiable token must be replaced");
    }

    @Test
    void aSafeRequestOnAnyRouteIssuesTheCookieWhenAbsentAndKeepsAnExistingOne() {
        List<String> issued = given().when().get("/api/v1/products")
                .getHeaders().getValues("Set-Cookie");
        assertTrue(issued.stream().anyMatch(h -> h.startsWith(config.csrf().cookieName() + "=")),
                "a safe request without cookie must receive one: " + issued);

        String existing = tokens.issue();
        List<String> onSecondCall = given().cookie(config.csrf().cookieName(), existing)
                .when().get("/api/v1/products")
                .getHeaders().getValues("Set-Cookie");
        assertTrue(onSecondCall.stream()
                        .noneMatch(h -> h.startsWith(config.csrf().cookieName() + "=")),
                "an existing token must not be re-issued: " + onSecondCall);
    }

    private static String cookieValue(String setCookie) {
        String firstAttribute = setCookie.split(";", 2)[0];
        return firstAttribute.substring(firstAttribute.indexOf('=') + 1);
    }

    /** Token signed by a service whose clock window has already elapsed. */
    private static String stale(CsrfTokenService shortLived) {
        String fresh = shortLived.issue();
        String random = fresh.substring(0, fresh.indexOf('.'));
        long past = java.time.Instant.now().minus(Duration.ofHours(1)).getEpochSecond();
        CsrfTokenService signer = shortLived;
        String payload = random + "." + past;
        return payload + "." + signatureOf(signer, payload);
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
