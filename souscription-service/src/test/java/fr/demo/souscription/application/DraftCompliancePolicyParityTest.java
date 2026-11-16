package fr.demo.souscription.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.DELETE;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Parity check for 35-souscription-service-rgpd-draft-compliance — static half.
 *
 * <p>No source baseline exists for these properties: persisting the draft is a personal-data store
 * the monolith did not have (CFG-011, BASELINE.md section 4.2 kept the subscriber's identity in
 * {@code HttpSession} for 20 minutes at most). The parity framing is that the migration must not
 * make the subscriber's data more exposed nor longer retained than the source made it. This class
 * proves the properties that need no database:
 *
 * <ul>
 *   <li>the retention value configured is {@code PT20M} — strict equivalence with the former
 *       {@code <session-timeout>20</session-timeout>}, not an independent tuning value;</li>
 *   <li>the retention job never names the contractual table: one shared policy would delete
 *       confirmed subscriptions after 20 minutes;</li>
 *   <li>the erasure entry point exists as a repository operation and is <b>not</b> exposed as an
 *       HTTP {@code @DELETE} — the {@code /v1} API has no end-user authentication, so an erasure
 *       endpoint would let anyone who learns a {@code draftId} destroy another user's journey;</li>
 *   <li>{@code COMPLIANCE_RGPD.md} carries the minimisation table, the processing register fields,
 *       the named at-rest encryption mechanism, the control query and a blocking DPO line.</li>
 * </ul>
 *
 * <p>The database-backed half is {@code DraftComplianceParityTest}, which requires a container
 * runtime; these assertions deliberately do not, so the compliance invariants are verified on every
 * build.
 */
class DraftCompliancePolicyParityTest {

    private static final Path API_CLASSES =
            Path.of("target", "classes", "fr", "demo", "souscription", "api");
    private static final Path PROPERTIES =
            Path.of("src", "main", "resources", "application.properties");
    private static final Path JOB = Path.of("src", "main", "java", "fr", "demo", "souscription",
            "application", "DraftRetentionJob.java");
    private static final Path REPOSITORY = Path.of("src", "main", "java", "fr", "demo",
            "souscription", "persistence", "DraftRepository.java");
    private static final Path COMPLIANCE = Path.of("COMPLIANCE_RGPD.md");
    private static final Path MIGRATIONS =
            Path.of("src", "main", "resources", "db", "migration");

    @Test
    void retentionIsConfiguredAsTwentyMinutes() throws IOException {
        String properties = Files.readString(PROPERTIES);
        assertTrue(properties.contains("horizon.draft.retention=PT20M"),
                "horizon.draft.retention must be PT20M: the retention period is the strict "
                        + "equivalent of the source session window, and changing it is a product "
                        + "and compliance decision, not a tuning choice");
        assertTrue(properties.contains("sslmode=require"),
                "sslmode=require must stay in force: it is the in-transit half of the encryption "
                        + "measure recorded in the processing register");
    }

    @Test
    void retentionJobNeverNamesTheContractualTable() throws IOException {
        String job = Files.readString(JOB);
        assertTrue(job.contains("subscription_draft") || job.contains("expiresAt"),
                "the purge must be scoped to the draft store");
        assertTrue(!job.contains("SubscriptionEntity") && !job.contains("SubscriptionRepository")
                        && !job.contains("subscriptions.subscriptions"),
                "the draft purge must not reference the contractual table: the two stores have "
                        + "different purposes, legal bases and retention periods, and a shared "
                        + "20-minute policy would delete confirmed subscriptions");
    }

    @Test
    void erasureExistsOperationallyAndIsNotAnHttpEndpoint()
            throws IOException, ClassNotFoundException {
        assertTrue(Files.readString(REPOSITORY).contains("deleteByDraftId"),
                "DraftRepository.deleteByDraftId(UUID) must exist: without an operational entry "
                        + "point an erasure request can only be served by ad-hoc SQL");

        List<String> deleteEndpoints = new ArrayList<>();
        try (Stream<Path> files = Files.walk(API_CLASSES)) {
            List<Path> classFiles = files
                    .filter(file -> file.toString().endsWith(".class"))
                    .filter(file -> !file.toString().contains("$"))
                    .sorted()
                    .toList();
            for (Path file : classFiles) {
                Class<?> type = Class.forName(className(file));
                for (Method method : type.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(DELETE.class)) {
                        deleteEndpoints.add(type.getSimpleName() + "#" + method.getName());
                    }
                }
            }
        }
        assertEquals(List.of(), deleteEndpoints,
                "no @DELETE endpoint may exist under api/: the service has no end-user "
                        + "authentication and draftId travels in URLs");
    }

    @Test
    void minimisationDecisionAndMigrationsAreConsistent() throws IOException {
        String document = Files.readString(COMPLIANCE);
        for (String column : List.of("first_name", "last_name", "email", "birth_date")) {
            assertTrue(document.contains("`" + column + "`"),
                    "the minimisation table must carry one row per personal-data column, missing: "
                            + column);
        }
        boolean dropped = document.contains("DROP COLUMN");
        boolean v3 = Files.exists(MIGRATIONS.resolve("V3__draft_minimisation.sql"));
        assertEquals(dropped, v3,
                "a recorded column removal must be expressed as V3__draft_minimisation.sql, and "
                        + "V3 must not exist if nothing was dropped: the schema, the entity "
                        + "mapping and the recorded decision must agree");
    }

    @Test
    void processingRegisterAndEvidenceAreRecorded() throws IOException {
        String document = Files.readString(COMPLIANCE);
        assertTrue(document.contains("finalite"), "the purpose must be recorded");
        assertTrue(document.contains("base legale"), "the legal basis must be recorded");
        assertTrue(document.contains("duree de conservation"),
                "the retention period must be recorded");
        assertTrue(document.contains("destinataires"), "the recipients must be recorded");
        assertTrue(document.contains("PT20M")
                        && document.contains("<session-timeout>20</session-timeout>"),
                "the retention must be recorded as a strict equivalence with the former session "
                        + "timeout, which is the only link to the behaviour the source had");
        assertTrue(document.contains(
                        "SELECT count(*) FROM subscriptions.subscription_draft WHERE expires_at"),
                "the control query must be documented: it is the evidence an auditor can run "
                        + "without reading code");
        assertTrue(document.contains("horizon.draft.purged"),
                "the continuous evidence metric must be named: a purge that fails silently has the "
                        + "longest time to detection of any defect here");
        assertTrue(document.contains("chiffrement de disque")
                        || document.contains("chiffrement au niveau du volume")
                        || document.contains("au niveau du\n  volume"),
                "the at-rest mechanism must be named concretely: 'the platform handles it' is not "
                        + "a verifiable answer");
        assertTrue(document.contains("BLOQUANT") || document.contains("bloquant"),
                "the DPO validation must be recorded as blocking for go-live: an unrecorded "
                        + "validation means the service can be deployed with an unapproved "
                        + "personal-data store");
    }

    private static String className(Path classFile) {
        String relative = API_CLASSES.relativize(classFile).toString()
                .replace(".class", "")
                .replace(java.io.File.separatorChar, '.');
        return "fr.demo.souscription.api." + relative;
    }
}
