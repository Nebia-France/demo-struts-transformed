package fr.demo.souscription.catalogue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity check for plan 20: the /v1 resilience contract is published, mirrored in the OpenAPI
 * description, and implemented nowhere in this service. Plan 20 publishes an engagement only, so the
 * parity criteria are documentary plus the absence of any provider-side fault tolerance.
 */
class ResilienceContractParityTest {

    private static final Pattern FAULT_TOLERANCE = Pattern.compile(
            "@(Timeout|Retry|Fallback|CircuitBreaker|Bulkhead)\\b");

    private static String readme;
    private static String properties;

    @BeforeAll
    static void readPublishedDocuments() throws Exception {
        readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        properties = Files.readString(Path.of("src/main/resources/application.properties"),
                StandardCharsets.UTF_8);
    }

    @Test
    void lesCinqEngagementsSontPubliesDansLeReadme() {
        assertTrue(readme.contains("## Contrat de resilience /v1"), "section de contrat absente");
        assertTrue(readme.contains("99.9%"), "disponibilite non publiee");
        assertTrue(readme.contains("p95 <= 25 ms"), "p95 serveur non publie");
        assertTrue(readme.contains("p99 <= 60 ms"), "p99 serveur non publie");
        assertTrue(readme.contains("p95 <= 100 ms"), "p95 observe par l'appelant non publie");
        assertTrue(readme.contains("**2 s**") && readme.contains("**1 retry**"),
                "politique appelant (timeout 2 s / 1 retry) non publiee");
        assertTrue(readme.contains("Cache-Control") && readme.contains("ETag"),
                "ligne de fraicheur des donnees absente");
    }

    @Test
    void lesEngagementsSontMiroirDansLaDescriptionOpenApi() {
        int key = properties.indexOf("quarkus.smallrye-openapi.info-description=");
        assertTrue(key >= 0, "cle info-description absente de application.properties");
        String description = properties.substring(key, properties.indexOf('\n', key) < 0
                ? properties.length() : properties.indexOf('\n', key));
        for (String value : List.of("99.9%", "25 ms", "60 ms", "100 ms", "2 s", "1 retry",
                "Cache-Control", "ETag")) {
            assertTrue(description.contains(value),
                    "engagement absent de la description OpenAPI : " + value);
        }
    }

    @Test
    void laFrontiereDImplementationEstEnoncee() {
        assertTrue(readme.contains("plan 28"), "mecanisme de tolerance aux pannes non attribue");
        assertTrue(readme.contains("plan 29"), "mecanisme Cache-Control / ETag non attribue");
        assertTrue(readme.contains("This section publishes the engagement only"),
                "la frontiere entre engagement publie et mecanisme implemente n'est pas enoncee");
    }

    @Test
    void lesDivergencesAvecLesContratsDeReferenceSontDesPointsOuverts() {
        assertTrue(readme.contains("Open points: divergence from the reference contracts"),
                "les divergences avec global/contracts ne sont pas enregistrees");
        for (String referenceValue : List.of("40 ms", "150 ms", "3 s")) {
            assertTrue(readme.contains(referenceValue),
                    "valeur de reference non confrontee : " + referenceValue);
        }
    }

    @Test
    void aucuneToleranceAuxPannesCoteFournisseur() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main"))) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (FAULT_TOLERANCE.matcher(Files.readString(source, StandardCharsets.UTF_8))
                        .find()) {
                    offenders.add(source.toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "la politique de tolerance aux pannes est cote appelant uniquement, trouvee dans : "
                        + offenders);
    }
}
