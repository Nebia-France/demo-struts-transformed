package fr.demo.souscription.application;

import jakarta.ws.rs.ServiceUnavailableException;
import java.sql.SQLException;

/**
 * Indisponibilite de l'enregistrement de la souscription (W4-T04, ecart FG-003).
 *
 * <p>Remplace la {@code SQLException} checked du source : {@code confirm(Draft)} ne declare plus
 * d'exception checked, l'echec technique est traduit ici et rendu par
 * {@link fr.demo.souscription.api.problem.StorageUnavailableMapper} en <b>503</b> et jamais en
 * 500 — aucune confirmation n'a ete emise, la saisie est conservee et la requete peut etre
 * rejouee a l'identique.
 *
 * <p>Le {@code SQLState} est porte pour la journalisation serveur uniquement : il ne doit jamais
 * apparaitre dans le corps de reponse.
 *
 * <p>Derive de {@link ServiceUnavailableException} : meme sans mapper installe, un echec de
 * stockage reste un 503 et ne peut pas devenir un 500.
 */
public class StorageUnavailableException extends ServiceUnavailableException {

    private final String sqlState;

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, (Long) null, cause);
        this.sqlState = sqlStateOf(cause);
    }

    /** {@code SQLState} de la cause, ou {@code null} si la cause n'est pas une erreur SQL. */
    public String sqlState() {
        return sqlState;
    }

    private static String sqlStateOf(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                return sql.getSQLState();
            }
            if (current == current.getCause()) {
                return null;
            }
        }
        return null;
    }
}
