package fr.demo.souscription.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

/**
 * Verrou de non-regression sur la sortie console JSON (NFG-014).
 */
@QuarkusTest
class JsonLoggingTest {

    private static final String JSON_KEY = "quarkus.log.console.json";

    @Test
    void jsonConsoleIsEnabledOutsideDevAndTest() {
        Config config = ConfigProvider.getConfig();

        // Valeur non profilee declaree dans application.properties : celle qui s'applique en prod.
        Optional<String> declared = applicationPropertiesValue(config, JSON_KEY);
        assertTrue(declared.isPresent(), JSON_KEY + " doit etre declare dans application.properties");
        assertTrue(Boolean.parseBoolean(declared.get()), JSON_KEY + " doit valoir true (profil prod)");

        // Le profil test desactive volontairement le JSON pour garder des logs lisibles.
        assertFalse(config.getValue(JSON_KEY, Boolean.class), "le profil test doit desactiver le JSON");
    }

    @Test
    void serviceNameFieldAndEcsFormatAreConfigured() {
        Config config = ConfigProvider.getConfig();

        assertEquals(
                "souscription-service",
                applicationPropertiesValue(config, "quarkus.log.console.json.additional-field.\"service.name\".value")
                        .orElse(null));
        assertEquals("ecs", applicationPropertiesValue(config, "quarkus.log.console.json.log-format").orElse(null));
    }

    private static Optional<String> applicationPropertiesValue(Config config, String key) {
        for (ConfigSource source : config.getConfigSources()) {
            if (source.getName().contains("application.properties")) {
                String value = source.getValue(key);
                if (value != null) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }
}
