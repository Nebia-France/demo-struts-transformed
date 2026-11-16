package fr.demo.souscription;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.math.BigDecimal;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Garde de build NFG-007 (W8-T05) : la duree de demarrage rapportee par Quarkus doit rester
 * inferieure a 3000 ms. Le seuil est ainsi impose par la porte {@code verify} et pas seulement
 * par {@code deploy/BENCHMARK_REPORT.md}.
 *
 * <p>{@link StartupLogCapture} est enregistre avant l'extension Quarkus : il pose un handler sur
 * la categorie {@code io.quarkus} avant le demarrage de l'application et retient la ligne
 * {@code "... started in X.XXXs"} emise une fois l'application prete. Le test relit cette ligne et
 * compare la valeur au seuil. La ligne transite par une propriete systeme parce que la classe de
 * test est rechargee par le chargeur de classes de Quarkus : un champ statique ne serait pas
 * partage entre l'extension et la methode de test.
 *
 * <p>Le profil de test du module s'applique (datasource inactive, pas de Dev Services) : la mesure
 * porte sur le demarrage du framework hors etape Flyway, conformement a la reference de NFG-007.
 * La mesure de bout en bout sur PostgreSQL reel, Flyway compris, est celle du banc
 * {@code deploy/bench}.
 */
@ExtendWith(StartupTimeIT.StartupLogCapture.class)
@QuarkusTest
@TestProfile(StartupTimeIT.OwnStartProfile.class)
class StartupTimeIT {

    static final long THRESHOLD_MILLIS = 3000;

    static final String CAPTURED_LINE = "nfg007.quarkus.startup.line";

    static final Pattern STARTED_IN =
            Pattern.compile("\\(powered by Quarkus [^)]+\\) started in ([0-9]+(?:\\.[0-9]+)?)s");

    @Test
    void reportedStartupIsBelowThreeSeconds() {
        String line = System.getProperty(CAPTURED_LINE);
        assertNotNull(line, "aucune ligne « started in » journalisee par io.quarkus au demarrage");
        Matcher matcher = STARTED_IN.matcher(line);
        assertTrue(matcher.find(), "ligne de demarrage illisible : " + line);
        long millis = new BigDecimal(matcher.group(1)).movePointRight(3).longValue();
        System.out.println("NFG-007 StartupTimeIT: Quarkus started in " + millis + " ms");
        assertTrue(millis < THRESHOLD_MILLIS,
                "NFG-007 : demarrage rapporte " + millis + " ms, seuil " + THRESHOLD_MILLIS + " ms");
    }

    /**
     * Profil propre a ce test, sans surcharge : un profil distinct force Quarkus a (re)demarrer
     * l'application pour cette classe, meme si une classe precedente du meme lancement failsafe
     * l'a deja demarree avec le profil par defaut. Sans lui, aucune ligne ne serait emise.
     */
    public static class OwnStartProfile implements QuarkusTestProfile {
    }

    /** Capture la ligne de demarrage de Quarkus ; doit preceder l'extension Quarkus. */
    public static class StartupLogCapture implements BeforeAllCallback {

        @Override
        public void beforeAll(ExtensionContext context) {
            System.clearProperty(CAPTURED_LINE);
            Logger.getLogger("io.quarkus").addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    String formatted = record instanceof ExtLogRecord ext
                            ? ext.getFormattedMessage() : record.getMessage();
                    if (formatted != null && STARTED_IN.matcher(formatted).find()) {
                        System.setProperty(CAPTURED_LINE, formatted);
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }
    }
}
