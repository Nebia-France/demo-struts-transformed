package fr.demo.souscription.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.ServiceUnavailableException;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Injection de fautes sur l'appel au catalogue (W6-T01) : les parametres verifies ici sont ceux
 * de {@code resilience-contract.json} et d'aucune autre source.
 *
 * <p>Les methodes sont ordonnees parce que le disjoncteur est un etat partage par le bean : la
 * fenetre glissante est remplie volontairement par le dernier test, apres les assertions de
 * reessai qui exigent un circuit ferme.
 */
@QuarkusTest
@TestProfile(RemoteCatalogueProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogueResilienceTest {

    private static CatalogueStubServer stub;

    @Inject
    CatalogueLookup lookup;

    @BeforeAll
    static void startStub() throws IOException {
        stub = new CatalogueStubServer();
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @Test
    @Order(1)
    void unQuatreCentQuatreNEstJamaisReessaye() {
        stub.reset();
        stub.serveNotFound();

        assertTrue(lookup.findProduct("INEXISTANT").isEmpty());
        assertEquals(1, stub.requests(),
                "toute reponse 4xx est une condition non rejouable du contrat : exactement une "
                + "requete doit atteindre le fournisseur");
    }

    @Test
    @Order(2)
    void unQuatreCentVingtNeufNEstJamaisReessayeEtDevientIndisponible() {
        stub.reset();
        stub.serveStatus(429);

        assertThrows(ServiceUnavailableException.class, () -> lookup.findProduct("PRUDENT"));
        assertEquals(1, stub.requests(), "le 429 ne doit jamais etre reessaye");
    }

    @Test
    @Order(3)
    void unCinqCentEstReessayeDeuxFois() {
        stub.reset();
        stub.serveStatus(500);

        assertThrows(ServiceUnavailableException.class, () -> lookup.findProduct("PRUDENT"));
        assertEquals(3, stub.requests(),
                "maxRetries = 2 : l'appel initial plus deux reessais, et aucun taux de repli");
    }

    @Test
    @Order(4)
    void leDelaiDepasseEstRenduIndisponible() {
        stub.reset();
        stub.serveSlowly();

        long start = System.nanoTime();
        assertThrows(ServiceUnavailableException.class, () -> lookup.findProduct("PRUDENT"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 5000,
                "le timeout de 3 s du contrat doit trancher avant la reponse du fournisseur ; "
                + "mesure=" + elapsedMillis + "ms");
    }

    @Test
    @Order(5)
    void leDisjoncteurSOuvreAuxSeuilsDuContrat() {
        stub.serveStatus(500);

        boolean opened = false;
        for (int call = 0; call < 40 && !opened; call++) {
            stub.reset();
            assertThrows(ServiceUnavailableException.class, () -> lookup.findProduct("PRUDENT"));
            opened = stub.requests() == 0;
        }

        assertTrue(opened,
                "apres 20 appels dont plus de la moitie en echec, le disjoncteur doit s'ouvrir et "
                + "l'appel echouer sans atteindre le fournisseur");
    }
}
