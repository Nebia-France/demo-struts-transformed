package fr.demo.souscription.simulation.service;

import fr.demo.souscription.simulation.model.Simulation;
import io.micrometer.core.annotation.Timed;
import java.math.BigDecimal;
import java.math.MathContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

@ApplicationScoped
public class SimulationService {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final Logger LOG = Logger.getLogger(SimulationService.class);

    /**
     * Audit timestamp source. The field initialiser keeps the ported unit tests, which
     * instantiate this class outside CDI, on the same Europe/Paris clock as the container.
     */
    @Inject
    Clock clock = Clock.system(ZoneId.of("Europe/Paris"));

    // CPU-bound : jusqu'à 360 itérations BigDecimal DECIMAL128 pour durationYears = 30 ;
    // ne doit jamais s'exécuter sur l'event loop Vert.x (l'annotation bloquante est posée sur la ressource REST).
    // Timer métier distinct de http_server_requests (NFG-001) : une régression doit pouvoir
    // être attribuée au calcul plutôt qu'au transport. Aucun tag : jamais de montant, de taux,
    // de corrélation id ni de trace id (cardinalité bornée).
    @Timed(value = "simulation.calculate", description = "Durée du calcul de projection")
    public Simulation calculate(BigDecimal initial, BigDecimal monthly, int years, BigDecimal annualRate) {
        if (initial == null || monthly == null || annualRate == null
                || initial.scale() > 2 || monthly.scale() > 2
                || initial.compareTo(new BigDecimal("100")) < 0
                || initial.compareTo(new BigDecimal("100000")) > 0
                || monthly.signum() < 0 || monthly.compareTo(new BigDecimal("2000")) > 0
                || years < 1 || years > 30 || annualRate.signum() < 0
                || annualRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Paramètres de simulation invalides");
        }
        BigDecimal factor = BigDecimal.ONE.add(annualRate.divide(new BigDecimal("12"), PRECISION));
        BigDecimal capital = initial;
        for (int month = 0; month < years * 12; month++) {
            capital = capital.multiply(factor, PRECISION).add(monthly, PRECISION);
        }
        BigDecimal contributed = initial.add(monthly.multiply(BigDecimal.valueOf(years * 12L)));
        Simulation result = new Simulation(initial.setScale(2), monthly.setScale(2), years,
                contributed.setScale(2), capital.setScale(2, RoundingMode.HALF_UP));
        audit(annualRate, result);
        return result;
    }

    /**
     * One structured line per projection, emitted after the result is built. Carries only
     * simulation figures: this service receives no identity, name, e-mail or birth date.
     */
    private void audit(BigDecimal annualRate, Simulation result) {
        String at = Instant.now(clock).toString();
        MDC.put("initialAmount", result.initial().toPlainString());
        MDC.put("monthlyAmount", result.monthly().toPlainString());
        MDC.put("durationYears", String.valueOf(result.years()));
        MDC.put("annualRate", annualRate.toPlainString());
        MDC.put("contributed", result.contributed().toPlainString());
        MDC.put("finalCapital", result.finalCapital().toPlainString());
        MDC.put("at", at);
        try {
            LOG.infov("simulation.computed initialAmount={0} monthlyAmount={1} durationYears={2}"
                    + " annualRate={3} contributed={4} finalCapital={5} at={6}",
                    result.initial().toPlainString(), result.monthly().toPlainString(),
                    result.years(), annualRate.toPlainString(),
                    result.contributed().toPlainString(), result.finalCapital().toPlainString(), at);
        } finally {
            MDC.remove("initialAmount");
            MDC.remove("monthlyAmount");
            MDC.remove("durationYears");
            MDC.remove("annualRate");
            MDC.remove("contributed");
            MDC.remove("finalCapital");
            MDC.remove("at");
        }
    }
}
