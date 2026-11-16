package fr.demo.souscription.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-regression guard of the non-blocking execution model (W5-T02).
 *
 * <p>The source {@code fr.demo.souscription.web.SecurityFilter} guarded its token with
 * {@code synchronized (session)}. That was only correct under thread-per-request, where blocking a
 * worker thread costs one request; on a Vert.x event loop the same lock stalls every request
 * multiplexed on that thread. This test fails the build if such a construct is reintroduced in
 * {@code src/main/java}.
 *
 * <p>It is a static scan on purpose: blocking the event loop does not surface in unit tests, only
 * under the load test of plan 24 (see {@code docs/execution-model.md}). A construct that genuinely
 * cannot be made reactive must be moved to a worker thread with
 * {@code @io.smallrye.common.annotation.Blocking} and documented there.
 */
class ExecutionModelTest {

    private static final Path MAIN_SOURCES = Paths.get("src", "main", "java");

    /** Forbidden construct -> why it may not appear on a request path. */
    private static final Map<String, String> FORBIDDEN = new LinkedHashMap<>();

    static {
        FORBIDDEN.put("synchronized", "a monitor held on an event loop stalls every multiplexed request");
        FORBIDDEN.put("Thread.sleep", "sleeping on an event loop freezes the whole I/O thread");
        FORBIDDEN.put("CountDownLatch", "latch waiting parks the event loop thread");
        FORBIDDEN.put(".await()", "awaiting a Uni/future result blocks instead of composing it");
        FORBIDDEN.put(".awaitSuspending(", "suspending await has no caller coroutine here, it blocks");
        FORBIDDEN.put("wait()", "Object.wait parks the event loop thread");
        FORBIDDEN.put("notify()", "monitor signalling implies a monitor wait on the same path");
        FORBIDDEN.put("notifyAll()", "monitor signalling implies a monitor wait on the same path");
        FORBIDDEN.put("Files.read", "file I/O is blocking; use the Vert.x file system API");
        FORBIDDEN.put("Files.write", "file I/O is blocking; use the Vert.x file system API");
        FORBIDDEN.put("Files.newInputStream", "stream file I/O is blocking");
        FORBIDDEN.put("Files.newOutputStream", "stream file I/O is blocking");
        FORBIDDEN.put("java.sql.", "no JDBC on the gateway: it owns no datastore and JDBC is blocking");
        FORBIDDEN.put("DriverManager", "no JDBC on the gateway: it owns no datastore and JDBC is blocking");
        FORBIDDEN.put("java.net.HttpURLConnection", "blocking HTTP client; the downstream edges use the reactive REST client");
        FORBIDDEN.put("HttpClient.newHttpClient", "blocking HTTP client; the downstream edges use the reactive REST client");
    }

    @Test
    void mainSourcesContainNoBlockingConstruct() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaSources()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String code = stripComment(lines.get(i));
                for (Map.Entry<String, String> entry : FORBIDDEN.entrySet()) {
                    if (code.contains(entry.getKey())) {
                        violations.add(file + ":" + (i + 1) + " -> '" + entry.getKey() + "' : "
                                + entry.getValue());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "blocking construct(s) reintroduced in src/main/java; make the logic reactive or"
                        + " annotate the method @io.smallrye.common.annotation.Blocking and document"
                        + " it in docs/execution-model.md:\n" + String.join("\n", violations));
    }

    /**
     * Every {@code @Blocking} method must be named in {@code docs/execution-model.md}, so the set of
     * methods running off the event loop stays an explicit, reviewed list (today: empty).
     */
    @Test
    void everyBlockingAnnotationIsDocumented() throws IOException {
        Path doc = Paths.get("docs", "execution-model.md");
        assertTrue(Files.exists(doc), "docs/execution-model.md must exist");
        String documentation = Files.readString(doc, StandardCharsets.UTF_8);
        List<String> undocumented = new ArrayList<>();
        for (Path file : javaSources()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (!stripComment(lines.get(i)).contains("@Blocking")) {
                    continue;
                }
                String owner = file.getFileName().toString().replace(".java", "");
                String method = methodNameAfter(lines, i);
                if (!documentation.contains(owner + "#" + method)) {
                    undocumented.add(file + ":" + (i + 1) + " -> " + owner + "#" + method);
                }
            }
        }
        assertTrue(undocumented.isEmpty(),
                "@Blocking methods must be listed as <Class>#<method> in docs/execution-model.md"
                        + " with their reason:\n" + String.join("\n", undocumented));
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        }
    }

    /** Drops line comments and javadoc/block-comment bodies so prose never trips the scan. */
    private static String stripComment(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
            return "";
        }
        int inline = line.indexOf("//");
        return inline < 0 ? line : line.substring(0, inline);
    }

    private static String methodNameAfter(List<String> lines, int annotationLine) {
        for (int i = annotationLine + 1; i < lines.size(); i++) {
            String code = stripComment(lines.get(i));
            int parenthesis = code.indexOf('(');
            if (parenthesis < 0) {
                continue;
            }
            String beforeParenthesis = code.substring(0, parenthesis).trim();
            int lastSpace = beforeParenthesis.lastIndexOf(' ');
            return lastSpace < 0 ? beforeParenthesis : beforeParenthesis.substring(lastSpace + 1);
        }
        return "<unknown>";
    }
}
