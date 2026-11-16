package fr.demo.souscription.infrastructure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Garde NFG-004 : aucun identifiant de base de donnees ne porte de valeur litterale dans
 * application.properties. Toute affectation d'une cle contenant {@code password},
 * {@code username} ou {@code jdbc.url} doit etre entierement enfermee dans une expression
 * {@code ${...}}, sans valeur par defaut. Test unitaire simple : il lit la ressource depuis le
 * chemin de classes, sans demarrer l'application.
 */
class NoLiteralSecretTest {

    private static final Pattern CREDENTIAL_KEY =
            Pattern.compile("(password|username|jdbc\\.url)\\s*$", Pattern.CASE_INSENSITIVE);

    /** Reference d'environnement sans valeur par defaut : ${NOM} et rien d'autre. */
    private static final Pattern ENV_REFERENCE = Pattern.compile("\\$\\{[A-Za-z0-9_.-]+}");

    @Test
    void aucunIdentifiantNEstUneValeurLitterale() throws IOException {
        List<String> violations = new ArrayList<>();
        int credentialAssignments = 0;

        try (InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("application.properties")) {
            assertTrue(in != null, "application.properties introuvable sur le chemin de classes");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator < 0) {
                    continue;
                }
                String key = trimmed.substring(0, separator).trim();
                String value = trimmed.substring(separator + 1).trim();
                if (!CREDENTIAL_KEY.matcher(key).find()) {
                    continue;
                }
                credentialAssignments++;
                if (!ENV_REFERENCE.matcher(value).matches()) {
                    violations.add("ligne " + number + " : " + key + " = " + value);
                }
            }
        }

        assertTrue(credentialAssignments >= 3,
                "les trois identifiants de base de donnees doivent etre declares, trouves : "
                        + credentialAssignments);
        assertTrue(violations.isEmpty(),
                "identifiant(s) non issus d'une reference d'environnement : " + violations);
    }

    /**
     * Garde de portee module : aucun autre fichier de configuration livre (profils auxiliaires
     * compris) ne porte un {@code username} ou un {@code password} litteral. Le fichier
     * {@code application-local-h2.properties} du plan source portait {@code sa} / mot de passe
     * vide : un identifiant en dur dans l'historique git, exactement le defaut que NFG-004
     * supprime.
     */
    @Test
    void aucunFichierDeConfigurationNePorteDIdentifiantLitteral() throws IOException {
        Path resources = Path.of("src", "main", "resources");
        assertTrue(Files.isDirectory(resources), "repertoire de ressources introuvable : " + resources);

        Pattern credential = Pattern.compile("(password|username)\\s*$", Pattern.CASE_INSENSITIVE);
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(resources)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .toList()) {
                int number = 0;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    number++;
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                        continue;
                    }
                    int separator = trimmed.indexOf('=');
                    if (separator < 0) {
                        continue;
                    }
                    String key = trimmed.substring(0, separator).trim();
                    String value = trimmed.substring(separator + 1).trim();
                    if (!credential.matcher(key).find()) {
                        continue;
                    }
                    if (!ENV_REFERENCE.matcher(value).matches()) {
                        violations.add(file + " ligne " + number + " : " + key + " = " + value);
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "identifiant(s) litteral(aux) dans la configuration livree : " + violations);
    }
}
