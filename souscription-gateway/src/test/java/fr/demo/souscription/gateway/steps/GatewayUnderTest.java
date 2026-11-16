package fr.demo.souscription.gateway.steps;

import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the packaged gateway once per Cucumber JVM so the glue can exercise real HTTP responses.
 *
 * <p>The suite runs under failsafe (profile {@code cucumber}) in the {@code verify} phase, after
 * {@code quarkus:build} has produced {@code target/quarkus-app/quarkus-run.jar}. The process is
 * started lazily by the {@code Given l'application Horizon est demarree} step and stopped by a JVM
 * shutdown hook.
 */
final class GatewayUnderTest {

    private static final Path RUN_JAR = Paths.get("target", "quarkus-app", "quarkus-run.jar");
    private static final int STARTUP_ATTEMPTS = 120;

    private static Process process;

    private GatewayUnderTest() {
    }

    static synchronized void ensureStarted(int port) {
        if (isUp(port)) {
            return;
        }
        requirePackagedApplication();
        process = launch(port, new File("target/gateway-cucumber-app.log"));
        Runtime.getRuntime().addShutdownHook(new Thread(GatewayUnderTest::stop));
        waitUntilUp(port);
    }

    /**
     * Starts one packaged gateway on {@code port} (management interface on {@code port + 1}),
     * wired to the contract-derived WireMock doubles, its journal written to {@code log}. The
     * caller owns the returned process. {@code extraProperties} are added to the JVM command line.
     */
    static Process launch(int port, File log, String... extraProperties) {
        try {
            // The three downstream providers are not deployed yet: the packaged application is
            // pointed at the same contract-derived WireMock doubles as the @QuarkusTest suite
            // (docs/downstream-clients.md).
            List<String> command = new ArrayList<>(List.of(
                    Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                    // The gate runs against plain-HTTP WireMock doubles, so the packaged
                    // application must not activate the %prod outbound mTLS block: its keystore and
                    // truststore are mounted from a cluster secret (docs/mtls.md) that no build
                    // agent has, and their absence fails every downstream call with a 500.
                    "-Dquarkus.profile=cucumber",
                    "-Dquarkus.http.port=" + port,
                    "-Dquarkus.management.port=" + (port + 1)));
            DownstreamWireMocks.start().forEach((key, baseUri) ->
                    command.add("-Dquarkus.rest-client." + key + ".url=" + baseUri));
            // Tracing is on in the packaged application (W6-T04): it exports over OTLP
            // http/protobuf to the local collector double, so the gate can assert on the exported
            // traces. The sampling ratio is the configured default.
            command.add("-Dquarkus.otel.exporter.otlp.endpoint=" + OtlpCollectorStub.start());
            command.add("-Dquarkus.otel.exporter.otlp.traces.protocol=http/protobuf");
            command.add("-Dquarkus.otel.bsp.schedule.delay=100ms");
            command.addAll(List.of(extraProperties));
            command.addAll(List.of("-jar", RUN_JAR.toString()));
            return new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start the packaged gateway", e);
        }
    }

    static void requirePackagedApplication() {
        if (!Files.isRegularFile(RUN_JAR)) {
            throw new IllegalStateException("Packaged application not found at " + RUN_JAR.toAbsolutePath()
                    + " - run: mvn -f souscription-gateway/pom.xml verify -Pcucumber");
        }
    }

    private static void waitUntilUp(int port) {
        for (int i = 0; i < STARTUP_ATTEMPTS; i++) {
            if (isUp(port)) {
                return;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the gateway", e);
            }
        }
        throw new IllegalStateException("The gateway did not answer on port " + port
                + " - see target/gateway-cucumber-app.log");
    }

    private static boolean isUp(int port) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(
                    "http://localhost:" + port + "/api/v1/products").toURL().openConnection();
            connection.setConnectTimeout(500);
            connection.setReadTimeout(2000);
            int status = connection.getResponseCode();
            connection.disconnect();
            return status > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
        }
        DownstreamWireMocks.stop();
        OtlpCollectorStub.stop();
    }
}
