package fr.demo.souscription.gateway.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * The mTLS expiry gauge reads the {@code notAfter} of the material the REST clients present
 * (W6-T02, plan 19). The key pair is self-signed and generated under {@code target/} by
 * {@code keytool}: no store is committed.
 */
class CertificateExpiryMetricsTest {

    private static final String PASSWORD = "test-only-expiry-password";

    @Test
    void earliestNotAfterIsTheCertificateValidityEnd() throws Exception {
        Path dir = Files.createDirectories(Path.of("target", "expiry-test"));
        Path store = dir.resolve("client-" + System.nanoTime() + ".p12");
        long before = Instant.now().getEpochSecond();
        Process keytool = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=souscription-gateway", "-validity", "30",
                "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD))
                .inheritIO().start();
        assertEquals(0, keytool.waitFor(), "keytool doit generer le magasin de test");

        OptionalLong notAfter = CertificateExpiryMetrics.earliestNotAfter(store, PASSWORD.toCharArray());

        assertTrue(notAfter.isPresent(), "le notAfter doit etre lu");
        long thirtyDays = 30L * 24 * 3600;
        assertTrue(Math.abs(notAfter.getAsLong() - (before + thirtyDays)) < 120,
                "le notAfter doit correspondre a la validite de 30 jours : " + notAfter.getAsLong());
    }

    @Test
    void unreadableStoreYieldsNoValue() {
        assertTrue(CertificateExpiryMetrics.earliestNotAfter(
                Path.of("target", "absent.p12"), PASSWORD.toCharArray()).isEmpty());
    }
}
