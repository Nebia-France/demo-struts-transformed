package fr.demo.souscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Parite du harnais de test (verification du plan 36). La mesure n'est pas le comportement des
 * tests migres -- ils sont l'instrument -- mais l'identite du harnais : JUnit 5 sans regression de
 * version, aucun test perdu ni desactive sans condition, aucune trace de Cargo / Tomcat / jsoup,
 * decoupage surefire / failsafe correct, suite Cucumber encore en dry-run et porte de build
 * promue documentee.
 */
class HarnessParityTest {

    private static final Path MODULE = Path.of(".").toAbsolutePath().normalize();
    private static final Path LEGACY = MODULE.getParent();

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> javaFilesUnder(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long countOccurrences(String haystack, Pattern needle) {
        return needle.matcher(haystack).results().count();
    }

    /** Etape 2 : le harnais est JUnit 5, aucun import JUnit 4 n'a ete introduit. */
    @Test
    void theHarnessIsJunit5Only() {
        Pattern junit4 = Pattern.compile("import\\s+(org\\.junit\\.(Test|Before|After|Assert)|junit\\.framework)");
        List<Path> offenders = javaFilesUnder(MODULE.resolve("src/test/java")).stream()
                .filter(p -> junit4.matcher(read(p)).find())
                .toList();
        assertTrue(offenders.isEmpty(), "imports JUnit 4 dans le harnais cible : " + offenders);

        List<Path> legacyTests = javaFilesUnder(LEGACY.resolve("src/test/java"));
        assertFalse(legacyTests.isEmpty(), "tests source introuvables");
        assertTrue(
                legacyTests.stream().allMatch(p -> read(p).contains("org.junit.jupiter")),
                "les tests source sont deja en JUnit 5 : aucune conversion 4 -> 5 n'est attendue");
    }

    /** Etape 3 : les 14 methodes @Test de la source sont intactes et le module en porte davantage. */
    @Test
    void noTestWasLost() {
        Pattern test = Pattern.compile("@Test\\b");
        long legacy = javaFilesUnder(LEGACY.resolve("src/test/java")).stream()
                .mapToLong(p -> countOccurrences(read(p), test))
                .sum();
        assertEquals(14, legacy, "les 14 @Test de la source doivent rester inchanges");

        long module = javaFilesUnder(MODULE.resolve("src/test/java")).stream()
                .mapToLong(p -> countOccurrences(read(p), test))
                .sum();
        assertTrue(module >= legacy, "le compte de tests du module ne doit pas diminuer : " + module);
    }

    /**
     * Etape 3 (suite) : aucun test desactive inconditionnellement. Les annotations conditionnelles
     * ({@code @RequiresContainerRuntime}, {@code @EnabledUnlessPropertyFalse}) sont
     * admises : elles refletent l'absence d'un daemon de conteneurs, pas une perte de couverture.
     */
    @Test
    void noTestIsUnconditionallyDisabled() {
        Pattern unconditional = Pattern.compile("@(Disabled|Ignore)\\s*(\\(|\\n|\\r|$)|assumeTrue\\(false\\)");
        List<Path> offenders = Stream.concat(
                        javaFilesUnder(MODULE.resolve("src/test/java")).stream(),
                        javaFilesUnder(LEGACY.resolve("src/test/java")).stream())
                .filter(p -> unconditional.matcher(read(p)).find())
                .toList();
        assertTrue(offenders.isEmpty(), "tests desactives sans condition : " + offenders);
    }

    /** Etapes 4 et 6 : ni Cargo, ni Tomcat, ni URL de base figee, ni jsoup dans le POM du module. */
    @Test
    void noServletContainerAndNoJsoupInTheModulePom() {
        String pom = read(MODULE.resolve("pom.xml"));
        String declarations = pom.replaceAll("(?s)<!--.*?-->", "");
        Pattern banned = Pattern.compile("(?i)cargo|tomcat|18080|<id>integration</id>|jsoup");
        assertFalse(
                banned.matcher(declarations).find(),
                "le module cible n'a aucun conteneur de servlets ni jsoup a declarer");
    }

    /** Etape 7 : surefire porte *Test.java, failsafe porte *IT.java. */
    @Test
    void theSurefireFailsafeSplitIsCorrect() {
        String pom = read(MODULE.resolve("pom.xml")).replaceAll("(?s)<!--.*?-->", "");
        int surefire = pom.indexOf("maven-surefire-plugin");
        int failsafe = pom.indexOf("maven-failsafe-plugin");
        assertTrue(surefire >= 0 && failsafe > surefire, "les deux plugins doivent etre configures");
        String surefireBlock = pom.substring(surefire, failsafe);
        assertTrue(surefireBlock.contains("<include>**/*Test.java</include>"), "surefire doit porter *Test.java");
        assertFalse(surefireBlock.contains("*IT.java"), "surefire ne doit pas executer les *IT.java");
        assertTrue(
                pom.substring(failsafe).contains("<include>**/*IT.java</include>"),
                "failsafe doit porter *IT.java");
    }

    /**
     * Etape 8 : le profil test rend la suite autonome -- Dev Services arme, Flyway
     * migrate-at-start arme, aucun indicateur de repli local (plan 41 : les clients REST sont la
     * seule implementation, reellement exercee, et les tests doivent les bouchonner) et OTel coupe.
     */
    @Test
    void theTestProfileMakesTheSuiteSelfContained() {
        String properties = read(MODULE.resolve("src/main/resources/application.properties"));
        assertTrue(
                properties.contains("%test.quarkus.flyway.migrate-at-start=true"),
                "sans migrate-at-start, le schema n'existe pas sous test");
        assertTrue(
                properties.contains("%test.quarkus.datasource.devservices.enabled=true"),
                "Dev Services doit etre arme sous test");
        assertFalse(
                properties.contains("horizon.clients."),
                "plan 41 : l'indicateur de repli local est retire, les clients REST sont seuls");
        assertTrue(properties.contains("%test.quarkus.otel.sdk.disabled=true"), "OTel doit etre coupe sous test");

        List<Path> remoteClientProfiles = javaFilesUnder(MODULE.resolve("src/test/java")).stream()
                .filter(p -> read(p).contains("\"quarkus.rest-client.catalogue.url\""))
                .toList();
        assertFalse(
                remoteClientProfiles.isEmpty(),
                "au moins un profil de test doit pointer les clients REST sur leurs bouchons");
    }

    /**
     * Etape 10 : la suite Cucumber est restee en dry-run jusqu'au plan 39 ; depuis le plan 39
     * (step definitions recablees sur /v1) elle s'execute, filtree sur les scenarios du service.
     */
    @Test
    void theCucumberSuiteLeavesDryRunOnlyWithPlan39() {
        String junitPlatform = read(MODULE.resolve("src/test/resources/junit-platform.properties"));
        assertTrue(
                junitPlatform.contains("cucumber.execution.dry-run=false"),
                "le dry-run est leve au plan 39, step definitions recablees");
        assertTrue(
                junitPlatform.contains(
                        "cucumber.filter.tags=not @hors-perimetre-souscription-service"),
                "les scenarios realloues aux applications soeurs sont exclus par etiquette");
    }

    /** Etape 11 : la porte de build promue est documentee. */
    @Test
    void thePromotedGateIsDocumented() {
        String readme = read(MODULE.resolve("README.md"));
        assertTrue(
                readme.contains("mvn -f souscription-service/pom.xml clean verify -Pcucumber"),
                "une porte de build non documentee n'est pas executee");
        assertTrue(readme.contains("plan 36") || readme.contains("plans 36"), "la promotion doit etre datee");
    }
}
