package fr.demo.souscription.gateway.edge;

import fr.demo.souscription.gateway.runtime.GatewayConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stateless CSRF token issuance and verification (W3-T02), replacing the session-stored token of
 * {@code fr.demo.souscription.web.SecurityFilter} and the hidden field of {@code csrf.jspf}.
 *
 * <p>The two security properties of the source filter are preserved verbatim:
 * <ul>
 *   <li>comparison with {@link MessageDigest#isEqual} on UTF-8 bytes, never {@code String.equals}:
 *       {@code equals} short-circuits on the first differing byte and leaks the common prefix
 *       length through its execution time (FG-002);</li>
 *   <li>rejection of any submitted token longer than the configured bound
 *       ({@code gateway.csrf.max-token-length}, 100 as in the source).</li>
 * </ul>
 *
 * <p>Two deliberate strengthenings: the token is 32 bytes of {@link SecureRandom} (256 bits) instead
 * of a random UUID (122 bits, and not specified as unpredictable), and it carries its
 * own issue timestamp signed with {@code gateway.csrf.signing-key} so that the former 20-minute
 * session window survives without server-side state. Expiry is verified server side: the cookie
 * {@code Max-Age} is a client-side hint only and a client may ignore it.
 *
 * <p>Token wire format: {@code <random>.<epochSeconds>.<hmac>}, all three parts Base64url without
 * padding, 98 characters in total — inside the preserved 100-character bound.
 */
@ApplicationScoped
public class CsrfTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int RANDOM_BYTES = 32;
    private static final String MAC_ALGORITHM = "HmacSHA256";

    private final GatewayConfig config;

    @Inject
    public CsrfTokenService(GatewayConfig config) {
        this.config = config;
    }

    /** Issues a fresh token: 256 bits of CSPRNG output, an issue timestamp and its signature. */
    public String issue() {
        byte[] raw = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(raw);
        String payload = ENCODER.encodeToString(raw) + "." + Instant.now().getEpochSecond();
        return payload + "." + sign(payload);
    }

    /**
     * Double-submit comparison between the cookie value and the header value.
     *
     * @param cookieValue value of the {@code gateway.csrf.cookie-name} cookie, may be {@code null}
     * @param headerValue value of the {@code gateway.csrf.header-name} header, may be {@code null}
     * @param maxLength maximum accepted length of the submitted token
     * @return {@code true} only when both values are present, bounded and byte-identical
     */
    public boolean matches(String cookieValue, String headerValue, int maxLength) {
        if (cookieValue == null || headerValue == null) {
            return false;
        }
        if (headerValue.length() > maxLength) {
            return false;
        }
        return MessageDigest.isEqual(cookieValue.getBytes(StandardCharsets.UTF_8),
                headerValue.getBytes(StandardCharsets.UTF_8));
    }

    /** Double-submit comparison using the configured bound. */
    public boolean matches(String cookieValue, String headerValue) {
        return matches(cookieValue, headerValue, config.csrf().maxTokenLength());
    }

    /**
     * Server-side lifetime check. A token whose shape or signature cannot be verified is reported
     * as expired: the caller then issues a fresh one rather than trusting client-supplied material.
     */
    public boolean isExpired(String token) {
        if (token == null || token.length() > config.csrf().maxTokenLength()) {
            return true;
        }
        int lastDot = token.lastIndexOf('.');
        if (lastDot < 0) {
            return true;
        }
        String payload = token.substring(0, lastDot);
        String signature = token.substring(lastDot + 1);
        if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        int separator = payload.lastIndexOf('.');
        if (separator < 0) {
            return true;
        }
        long issuedAt;
        try {
            issuedAt = Long.parseLong(payload.substring(separator + 1));
        } catch (NumberFormatException malformed) {
            return true;
        }
        Instant deadline = Instant.ofEpochSecond(issuedAt).plus(ttl());
        return !Instant.now().isBefore(deadline);
    }

    /** Lifetime of an issued token, also used as the cookie {@code Max-Age}. */
    public Duration ttl() {
        return config.csrf().ttl();
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    config.csrf().signingKey().getBytes(StandardCharsets.UTF_8), MAC_ALGORITHM));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException unsupported) {
            throw new IllegalStateException("HmacSHA256 unavailable", unsupported);
        }
    }
}
