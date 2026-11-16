package fr.demo.souscription.application;

import java.util.UUID;

/**
 * Violation de l'unicite de {@code confirmation_key} detectee a l'interieur de la transaction de
 * confirmation (W4-T05).
 *
 * <p>Signal interne a {@link ConfirmationService} : deux confirmations concurrentes de la meme
 * cle ont franchi la lecture initiale, la seconde a heurte la contrainte unique
 * (SQLState {@code 23505}). L'exception n'est jamais propagee a la couche REST, elle declenche la
 * relecture idempotente dans une transaction neuve — la transaction courante etant marquee
 * rollback-only.
 */
public class DuplicateConfirmationException extends RuntimeException {

    private final UUID confirmationKey;

    public DuplicateConfirmationException(UUID confirmationKey, Throwable cause) {
        super("confirmation deja enregistree pour cette cle : " + confirmationKey, cause);
        this.confirmationKey = confirmationKey;
    }

    public UUID confirmationKey() {
        return confirmationKey;
    }
}
