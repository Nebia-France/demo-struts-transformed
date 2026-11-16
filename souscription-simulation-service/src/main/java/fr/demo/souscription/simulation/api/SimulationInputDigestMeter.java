package fr.demo.souscription.simulation.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mesure le taux d'entrées dupliquées (W4-T05) pour décider d'un cache sur une base
 * factuelle, sans en activer un. Aucune valeur métier n'est exposée : les compteurs ne
 * portent aucun tag (jamais de montant, de taux ni de corrélation id), seule l'empreinte
 * entière des paramètres est conservée, dans une fenêtre bornée de {@value #WINDOW}
 * entrées (LRU) — empreinte mémoire constante, pas un cache de résultats.
 */
@ApplicationScoped
public class SimulationInputDigestMeter {

    /** Taille fixe de la fenêtre d'empreintes récentes (borne mémoire). */
    static final int WINDOW = 1024;

    private final Counter distinct;
    private final Counter repeated;

    /** LRU bornée d'empreintes ; accès toujours sous verrou (appelée depuis plusieurs workers). */
    private final Map<Integer, Boolean> recent = new LinkedHashMap<>(WINDOW * 2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
            return size() > WINDOW;
        }
    };

    SimulationInputDigestMeter(MeterRegistry registry) {
        this.distinct = Counter.builder("simulation.inputs.distinct")
                .description("Simulation requests whose input parameters were not seen in the recent window")
                .register(registry);
        this.repeated = Counter.builder("simulation.inputs.repeated")
                .description("Simulation requests whose input parameters were already seen in the recent window")
                .register(registry);
    }

    /** Incrémente le compteur distinct ou repeated selon la fenêtre récente. */
    public void record(SimulationRequest request) {
        int digest = digest(request);
        boolean alreadySeen;
        synchronized (recent) {
            alreadySeen = recent.put(digest, Boolean.TRUE) != null;
        }
        if (alreadySeen) {
            repeated.increment();
        } else {
            distinct.increment();
        }
    }

    /**
     * Empreinte insensible à l'échelle : 1000 et 1000.00 sont la même entrée de calcul,
     * comme pour le résultat (le calcul est pur).
     */
    private static int digest(SimulationRequest request) {
        int result = plain(request.initialAmount());
        result = 31 * result + plain(request.monthlyAmount());
        result = 31 * result + plain(request.annualRate());
        return 31 * result + request.durationYears();
    }

    private static int plain(java.math.BigDecimal value) {
        return value == null ? 0 : value.stripTrailingZeros().toPlainString().hashCode();
    }
}
