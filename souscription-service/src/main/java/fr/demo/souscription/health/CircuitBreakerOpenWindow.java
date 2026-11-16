package fr.demo.souscription.health;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Lecture de l'etat d'un disjoncteur SmallRye Fault Tolerance depuis les jauges Micrometer, et
 * decision « ouvert de maniere durable » (W6-T06).
 *
 * <p>L'etat n'est pas re-suivi ici : la seule source est la jauge
 * {@code ft.circuitbreaker.state.total{method=...,state="open"}}, qui cumule le temps passe par le
 * disjoncteur a l'etat ouvert. Reimplementer un suivi d'etat dans la sonde produirait une seconde
 * verite, susceptible de diverger de celle que les tableaux de bord affichent.
 *
 * <p><b>Pourquoi une fenetre et pas l'etat instantane :</b> une readiness qui tombe a
 * {@code DOWN} au premier echec retire l'instance du Service alors que la panne est chez le
 * fournisseur et touche donc toutes les instances a la fois — le trafic n'a nulle part a aller, et
 * les operations qui n'appellent pas la dependance (lecture de brouillon, lecture de souscription)
 * cessent d'etre servies sans raison. Le signal retenu est donc : le disjoncteur a passe du temps
 * ouvert sur <b>chaque</b> intervalle entre deux interrogations consecutives de la sonde, pendant
 * au moins {@link #DURABLY_OPEN}. Une ouverture transitoire (un echec isole, puis fermeture)
 * interrompt la progression du cumul et remet la fenetre a zero.
 */
final class CircuitBreakerOpenWindow {

    /**
     * Duree minimale d'ouverture continue avant de se declarer inapte. Superieure au {@code delay}
     * de 10 s du disjoncteur : une ouverture unique suivie d'un retablissement reussi (seuil de
     * retablissement 2) ne peut donc pas franchir ce seuil, il faut au moins trois cycles
     * d'ouverture consecutifs.
     */
    static final Duration DURABLY_OPEN = Duration.ofSeconds(30);

    private static final String STATE_METRIC = "ft.circuitbreaker.state.total";

    /** Fragment discriminant de l'etiquette {@code method} de la jauge. */
    private final String methodMarker;

    private long previousOpenMillis = -1;

    private long openSinceMillis;

    CircuitBreakerOpenWindow(String methodMarker) {
        this.methodMarker = methodMarker;
    }

    /**
     * @return la duree d'ouverture continue observee, ou {@link Duration#ZERO} si le disjoncteur
     *         n'a pas progresse vers l'etat ouvert depuis l'interrogation precedente.
     */
    synchronized Duration continuouslyOpenFor(MeterRegistry registry, long nowMillis) {
        long openMillis = openMillis(registry);
        if (openMillis < 0) {
            // Aucune jauge : la methode gardee n'a encore jamais ete invoquee. Aucune panne
            // observee n'est aussi une raison de rester en rotation.
            previousOpenMillis = -1;
            openSinceMillis = 0;
            return Duration.ZERO;
        }
        boolean progressing = previousOpenMillis >= 0 && openMillis > previousOpenMillis;
        previousOpenMillis = openMillis;
        if (!progressing) {
            openSinceMillis = 0;
            return Duration.ZERO;
        }
        if (openSinceMillis == 0) {
            openSinceMillis = nowMillis;
        }
        return Duration.ofMillis(nowMillis - openSinceMillis);
    }

    /** Cumul d'ouverture en millisecondes, ou {@code -1} si la jauge n'est pas enregistree. */
    private long openMillis(MeterRegistry registry) {
        for (TimeGauge gauge : registry.find(STATE_METRIC).tag("state", "open").timeGauges()) {
            String method = gauge.getId().getTag("method");
            if (method != null && method.contains(methodMarker)) {
                return (long) gauge.value(TimeUnit.MILLISECONDS);
            }
        }
        return -1;
    }
}
