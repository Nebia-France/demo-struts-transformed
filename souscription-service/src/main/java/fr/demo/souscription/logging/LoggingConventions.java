package fr.demo.souscription.logging;

/**
 * Conventions de journalisation de souscription-service (W0-T07).
 *
 * <p>Le backend est celui de Quarkus ({@code org.jboss.logging.Logger}) avec une sortie console
 * JSON ({@code quarkus.log.console.json=true}). Les mecanismes source (journalisation via le
 * contexte de l'application web Struts et binding {@code org.slf4j:slf4j-simple}) ne doivent
 * jamais etre reintroduits.
 *
 * <p>Regles applicables a toute classe du module :
 * <ul>
 *   <li>le logger est declare {@code private static final org.jboss.logging.Logger}, obtenu via
 *       {@code Logger.getLogger(MaClasse.class)} : il est donc nomme d'apres la classe
 *       declarante ;</li>
 *   <li>les messages sont parametres ({@code debugf}, {@code infof}, {@code warnf},
 *       {@code errorf}) et jamais construits par concatenation de chaines ;</li>
 *   <li>aucune trace d'exception n'est journalisee au niveau {@code INFO} : une stacktrace
 *       appartient a {@code WARN} ou {@code ERROR}.</li>
 * </ul>
 *
 * <p>Exemple :
 * <pre>{@code
 * private static final Logger LOG = Logger.getLogger(ConfirmationService.class);
 * LOG.warnf("Enregistrement refuse ; sqlState=%s", sqlState);
 * }</pre>
 *
 * <p><strong>Diagnostic en attente de portage</strong> : le message source
 * {@code "Enregistrement refuse ; SQLState=" + ex.getSQLState()} (branche SQLException de
 * {@code SubscriptionAction}) n'a pas encore de site d'emission dans ce module, faute de code de
 * confirmation. Il est porte sous la forme
 * {@code LOG.warnf("Enregistrement refuse ; sqlState=%s", sqlState)} par les plans
 * {@code 17-souscription-service-rest-confirmation} et
 * {@code 23-souscription-service-idempotent-confirm}. Le diagnostic de demarrage
 * (initialisation du contexte web de la source) est porte par le plan {@code 25-souscription-service-health-probes}
 * (observateur de demarrage et sonde de readiness). Le diagnostic de liberation du pilote JDBC
 * (destruction du contexte web de la source) est abandonne : Agroal est proprietaire de l'arret (FG-029), ce qui est
 * consigne dans {@code CONFIG_MAPPING.md}.
 *
 * <p>La correlation par champs est en place (W5-T01) : {@code correlationId}, {@code traceId} et
 * {@code spanId} sont poses dans le MDC par {@code observability.CorrelationFilter} et serialises
 * dans chaque ligne JSON ; aucun site d'appel n'a a les citer. Les champs {@code firstName},
 * {@code lastName}, {@code email} et {@code birthDate} ne sont jamais journalises : un brouillon
 * est journalise par son {@code draftId} seul. Cf. {@code deploy/OBSERVABILITY.md}.
 */
public class LoggingConventions {

    private LoggingConventions() {
        // Classe utilitaire de documentation : aucune instance.
    }
}
