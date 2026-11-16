package fr.demo.souscription.integration;

import jakarta.enterprise.inject.spi.CDI;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sonde de test posee sur le client du catalogue : enregistre, au moment ou la requete sortante
 * part, si une transaction JTA est active sur le fil appelant.
 *
 * <p>C'est la seule preuve d'execution du critere NFG-009 : un appel de 3 s a l'interieur d'une
 * transaction retient une connexion du pool (max-size 10) pendant toute sa duree.
 */
public class TransactionProbeFilter implements ClientRequestFilter {

    private static final AtomicInteger OBSERVED = new AtomicInteger();
    private static final AtomicBoolean TRANSACTION_SEEN = new AtomicBoolean();

    static void reset() {
        OBSERVED.set(0);
        TRANSACTION_SEEN.set(false);
    }

    static int observed() {
        return OBSERVED.get();
    }

    static boolean transactionSeen() {
        return TRANSACTION_SEEN.get();
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        OBSERVED.incrementAndGet();
        if (active()) {
            TRANSACTION_SEEN.set(true);
        }
    }

    private static boolean active() {
        try {
            TransactionManager transactions = CDI.current().select(TransactionManager.class).get();
            return transactions.getStatus() != Status.STATUS_NO_TRANSACTION;
        } catch (RuntimeException | jakarta.transaction.SystemException unavailable) {
            return false;
        }
    }
}
