package fr.demo.souscription;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Garde du portage des tests unitaires source (W8-T03).
 *
 * <p>Lit {@code TEST_PORT_MATRIX.md} depuis le classpath de test et echoue si une ligne porte un
 * statut autre que {@code ported} / {@code ported-adapted}, si le nombre de lignes est inferieur
 * au nombre de methodes {@code @Test} des quatre fichiers source et de {@code SubscriptionIT}, si une methode source n'a pas de
 * ligne, ou si la methode cible designee n'existe pas dans le fichier cible.
 */
class TestPortCoverageTest {

    private static final Path MODULE = Path.of(".").toAbsolutePath().normalize();
    private static final Path LEGACY_TESTS =
            MODULE.getParent().resolve("src/test/java/fr/demo/souscription");
    private static final Path MODULE_TESTS = MODULE.resolve("src/test/java/fr/demo/souscription");

    private static final List<String> SOURCE_FILES = List.of(
            "service/SubscriptionServiceTest.java",
            "service/InputValidatorTest.java",
            "service/SimulationServiceTest.java",
            "repository/SubscriptionRepositoryTest.java",
            "web/SubscriptionIT.java");

    /** Methode de test JUnit 5 : {@code @Test} ou {@code @ParameterizedTest}, puis sa signature. */
    private static final Pattern TEST_METHOD = Pattern.compile(
            "@(Test|ParameterizedTest)\\b[^;]*?\\bvoid\\s+(\\w+)\\s*\\(", Pattern.DOTALL);

    private record Row(String sourceFile, String sourceMethod, String targetFile,
                       String targetMethod, String status) {
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Row> matrix() {
        try (InputStream in = TestPortCoverageTest.class.getResourceAsStream("/TEST_PORT_MATRIX.md")) {
            assertNotNull(in, "TEST_PORT_MATRIX.md absent du classpath de test");
            List<Row> rows = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("|") || trimmed.startsWith("|---")
                        || trimmed.startsWith("| source file")) {
                    continue;
                }
                String[] cells = trimmed.substring(1, trimmed.length() - 1).split("\\|", -1);
                assertTrue(cells.length == 5, "ligne mal formee : " + line);
                rows.add(new Row(cells[0].trim(), cells[1].trim(), cells[2].trim(),
                        cells[3].trim(), cells[4].trim()));
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> sourceMethods() {
        Set<String> methods = new TreeSet<>();
        for (String file : SOURCE_FILES) {
            Matcher matcher = TEST_METHOD.matcher(read(LEGACY_TESTS.resolve(file)));
            while (matcher.find()) {
                methods.add(file + "#" + matcher.group(2));
            }
        }
        return methods;
    }

    private static long sourceAtTestCount() {
        Pattern atTest = Pattern.compile("@Test\\b");
        return SOURCE_FILES.stream()
                .mapToLong(file -> atTest.matcher(read(LEGACY_TESTS.resolve(file))).results().count())
                .sum();
    }

    @Test
    void everyRowIsPortedOrPortedAdapted() {
        List<Row> rows = matrix();
        assertFalse(rows.isEmpty(), "la matrice ne contient aucune ligne");
        for (Row row : rows) {
            boolean ported = row.status().equals("ported");
            boolean adapted = row.status().startsWith("ported-adapted:")
                    && row.status().length() > "ported-adapted:".length() + 1;
            assertTrue(ported || adapted, "statut refuse (attendu ported ou ported-adapted"
                    + " avec raison) : " + row);
        }
    }

    @Test
    void rowCountCoversEverySourceTestMethod() {
        List<Row> rows = matrix();
        assertTrue(rows.size() >= sourceAtTestCount(),
                "moins de lignes (" + rows.size() + ") que de @Test source (" + sourceAtTestCount() + ")");

        Set<String> listed = new TreeSet<>();
        rows.forEach(row -> listed.add(row.sourceFile() + "#" + row.sourceMethod()));
        Set<String> missing = new TreeSet<>(sourceMethods());
        missing.removeAll(listed);
        assertTrue(missing.isEmpty(), "methodes source sans ligne dans la matrice : " + missing);
        assertTrue(rows.size() >= sourceMethods().size(),
                "chaque methode source, parametree comprise, doit avoir sa ligne");
    }

    @Test
    void everyTargetMethodExistsInItsTargetFile() {
        for (Row row : matrix()) {
            Path target = MODULE_TESTS.resolve(row.targetFile());
            assertTrue(Files.isRegularFile(target), "fichier cible introuvable : " + target);
            Matcher matcher = TEST_METHOD.matcher(read(target));
            boolean found = false;
            while (matcher.find()) {
                found |= matcher.group(2).equals(row.targetMethod());
            }
            assertTrue(found, "methode de test cible introuvable : " + row.targetFile() + "#"
                    + row.targetMethod());
        }
    }

    /** Etape 7 : aucune annotation de desactivation ni hypothese toujours fausse dans le module. */
    @Test
    void noTestInTheModuleIsDisabledOrIgnored() throws IOException {
        Pattern disabling = Pattern.compile("@(Disabled|Ignore)\\w*|assumeTrue\\s*\\(\\s*false\\s*\\)");
        try (Stream<Path> files = Files.walk(MODULE.resolve("src/test"))) {
            List<Path> offenders = files.filter(Files::isRegularFile)
                    .filter(p -> disabling.matcher(read(p)).find())
                    .toList();
            assertTrue(offenders.isEmpty(), "tests desactives ou ignores : " + offenders);
        }
    }
}
