package fr.demo.souscription.application;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;

/**
 * Conflit entre la requete et l'etat courant du brouillon (W4-T04).
 *
 * <p>Couvre les deux cas de conflit du parcours, rendus en <b>409</b> par
 * {@link fr.demo.souscription.api.problem.DraftStateMapper} :
 * <ul>
 *   <li>cle de confirmation qui ne correspond plus au brouillon
 *       ({@link #CONFIRMATION_KEY_MISMATCH}) ;</li>
 *   <li>parcours incomplet, souscripteur ou simulation manquant
 *       ({@link ConfirmationService#PRECONDITION_SEQUENCE}).</li>
 * </ul>
 *
 * <p>Exception non checked : la couche REST ne la rattrape pas, le mapper produit le corps
 * {@code application/problem+json}. Derive de {@link ClientErrorException} avec le statut
 * {@code CONFLICT} : meme sans mapper installe, le statut reste un 409.
 */
public class DraftStateException extends ClientErrorException {

    /** Code stable de la cle de confirmation divergente. */
    public static final String CONFIRMATION_KEY_MISMATCH = "CONFIRMATION_KEY_MISMATCH";

    /**
     * Code stable de la mise a jour concurrente du brouillon (W4-T06) : le verrou optimiste
     * {@code @Version} a rejete la seconde ecriture. Remplace le bloc de synchronisation sur la session
     * de la source ; le SPA reaffiche l'etat courant du brouillon.
     */
    public static final String DRAFT_CONCURRENT_UPDATE = "DRAFT_CONCURRENT_UPDATE";

    /** Champ porteur du conflit dans le tableau {@code errors} du corps problem+json. */
    private final String field;

    private final String code;

    public DraftStateException(String field, String code, String message) {
        super(message, Response.Status.CONFLICT);
        this.field = field;
        this.code = code;
    }

    public String field() {
        return field;
    }

    public String code() {
        return code;
    }
}
