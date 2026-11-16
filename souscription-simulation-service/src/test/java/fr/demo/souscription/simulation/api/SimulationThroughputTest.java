package fr.demo.souscription.simulation.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.simulation.service.SimulationService;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.math.BigDecimal;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * W4-T02 : coût CPU par requête du pire cas (durationYears = 30, soit 360 itérations
 * BigDecimal DECIMAL128). Mesure le débit soutenable par cœur et justifie le
 * dimensionnement de {@code quarkus.thread-pool.max-threads} et de la file d'attente.
 * Les chiffres mesurés sont reportés dans le README.
 */
class SimulationThroughputTest {

    private static final BigDecimal INITIAL = new BigDecimal("100000.00");
    private static final BigDecimal MONTHLY = new BigDecimal("2000.00");
    private static final BigDecimal RATE = new BigDecimal("0.10");
    private static final int YEARS = 30;

    private static final int WARMUP = 1_000;
    private static final int MEASURED = 5_000;

    /** Objectif de dimensionnement (exit criteria) : 500 simulations/s/cœur. */
    private static final double MIN_PER_CORE_THROUGHPUT = 500.0;

    @Test
    void worstCaseProjectionSustainsAtLeastFiveHundredSimulationsPerSecondPerCore() {
        SimulationService service = new SimulationService();

        for (int i = 0; i < WARMUP; i++) {
            service.calculate(INITIAL, MONTHLY, YEARS, RATE);
        }

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        boolean cpuTimeAvailable = threads.isCurrentThreadCpuTimeSupported();
        long startCpu = cpuTimeAvailable ? threads.getCurrentThreadCpuTime() : 0L;
        long startWall = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) {
            service.calculate(INITIAL, MONTHLY, YEARS, RATE);
        }
        long wallNanos = System.nanoTime() - startWall;
        long cpuNanos = cpuTimeAvailable ? threads.getCurrentThreadCpuTime() - startCpu : wallNanos;

        double cpuMicrosPerRequest = cpuNanos / 1_000.0 / MEASURED;
        double perCoreThroughput = 1_000_000.0 / cpuMicrosPerRequest;

        // Dimensionnement dérivé : max-threads = 16 (2x les 8 cœurs de référence, marge pour
        // le cas où un worker est momentanément descheduled), queue-size = 64 (~4 requêtes en
        // attente par thread, soit une latence d'attente bornée au pire cas).
        System.out.printf(Locale.ROOT,
            "simulation.sizing worstCase=durationYears:%d cpuMicrosPerRequest=%.1f"
                + " perCoreThroughput=%.0f/s coreThreads=8 maxThreads=16 queueSize=64%n",
            YEARS, cpuMicrosPerRequest, perCoreThroughput);

        assertTrue(perCoreThroughput >= MIN_PER_CORE_THROUGHPUT,
            () -> String.format(Locale.ROOT, "worst-case throughput %.0f sim/s/core is below the %.0f target"
                + " (%.1f us CPU per request)",
                perCoreThroughput, MIN_PER_CORE_THROUGHPUT, cpuMicrosPerRequest));
    }
}
