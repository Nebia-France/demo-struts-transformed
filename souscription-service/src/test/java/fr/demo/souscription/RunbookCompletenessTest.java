package fr.demo.souscription;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Garde de completude du runbook de mise en service (W8-T07, plan 43).
 *
 * <p>Lit {@code GO_LIVE_CHECKLIST.md} depuis le repertoire du module et echoue si l'un des 15
 * composants cibles de {@code apps/souscription-service/TARGET_ARCHITECTURE_MODEL.json} n'a pas de
 * ligne nommant au moins un plan realisant et au moins une classe de test existante, ou si la ligne
 * de validation DPO, marquee bloquante, est absente.
 */
class RunbookCompletenessTest {

    private static final Path MODULE = Path.of(".").toAbsolutePath().normalize();
    private static final Path CHECKLIST = MODULE.resolve("GO_LIVE_CHECKLIST.md");
    private static final Path MODULE_TESTS = MODULE.resolve("src/test/java");

    private static final List<String> TARGET_COMPONENTS = List.of(
            "runtimeConfiguration",
            "subscriptionRepository",
            "schemaMigrations",
            "draftRepository",
            "draftService",
            "domainEntities",
            "subscriptionDtos",
            "subscriptionResource",
            "requestValidation",
            "problemExceptionMappers",
            "confirmationService",
            "observability",
            "catalogueRestClient",
            "simulationRestClient",
            "transportSecurity");

    private static final Pattern PLAN = Pattern.compile("\\b\\d{2}\\b");
    private static final Pattern TEST_CLASS = Pattern.compile("`([A-Z][A-Za-z0-9]*(?:Test|IT))`");

    @Test
    void everyTargetComponentIsMappedToAPlanAndAnExistingTest() throws IOException {
        List<String> lines = Files.readAllLines(CHECKLIST, StandardCharsets.UTF_8);
        List<String> problems = new ArrayList<>();
        for (String component : TARGET_COMPONENTS) {
            List<String> rows = lines.stream()
                    .filter(line -> line.startsWith("| `" + component + "` |"))
                    .toList();
            if (rows.size() != 1) {
                problems.add(component + ": " + rows.size() + " ligne(s) au lieu d'une");
                continue;
            }
            String[] cells = rows.get(0).split("\\|");
            // cells[0] vide, [1] composant, [2] description, [3] plans, [4] tests
            if (cells.length < 5) {
                problems.add(component + ": ligne incomplete");
                continue;
            }
            if (!PLAN.matcher(cells[3]).find()) {
                problems.add(component + ": aucun plan realisant");
            }
            Matcher tests = TEST_CLASS.matcher(cells[4]);
            int found = 0;
            while (tests.find()) {
                found++;
                if (!testClassExists(tests.group(1))) {
                    problems.add(component + ": classe de test introuvable " + tests.group(1));
                }
            }
            if (found == 0) {
                problems.add(component + ": aucun test couvrant");
            }
        }
        assertTrue(problems.isEmpty(), "Composants non couverts : " + problems);
    }

    @Test
    void dpoValidationIsRecordedAsBlocking() throws IOException {
        List<String> dpoLines = Files.readAllLines(CHECKLIST, StandardCharsets.UTF_8).stream()
                .filter(line -> line.contains("DPO validation"))
                .toList();
        assertFalse(dpoLines.isEmpty(), "Ligne de validation DPO absente de GO_LIVE_CHECKLIST.md");
        assertTrue(dpoLines.stream()
                        .map(line -> line.toUpperCase(Locale.ROOT))
                        .anyMatch(line -> line.contains("BLOQUANT") || line.contains("BLOCKING")),
                "La validation DPO doit etre marquee bloquante");
        assertTrue(dpoLines.stream().anyMatch(line -> line.contains("COMPLIANCE_RGPD.md")),
                "La validation DPO doit renvoyer a COMPLIANCE_RGPD.md");
    }

    private static boolean testClassExists(String simpleName) throws IOException {
        try (Stream<Path> files = Files.walk(MODULE_TESTS)) {
            return files.anyMatch(path -> path.getFileName().toString().equals(simpleName + ".java"));
        }
    }
}
