package fr.demo.souscription.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.ProductDetail;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test de contrat consommateur du catalogue (W6-T01).
 *
 * <p>Couvre les trois scenarios de {@code features/catalogue/catalogue.feature} consommes par ce
 * service, contre un bouchon dont les reponses sont derivees du contrat gele
 * ({@code openapi.yaml} et {@code shared-schemas/ProductDetail.json}) :
 * <ul>
 *   <li>Consultation de la fiche d'un placement existant ;</li>
 *   <li>Le taux annuel est egalement expose en pourcentage ;</li>
 *   <li>Consultation de la fiche d'un placement inconnu.</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(RemoteCatalogueContractProfile.class)
class CatalogueClientContractTest {

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

    @BeforeEach
    void resetStub() {
        stub.reset();
        stub.serveProduct();
    }

    @Test
    void consultationDeLaFicheDUnPlacementExistant() {
        ProductDetail product = lookup.findProduct("PRUDENT").orElseThrow();

        assertEquals("PRUDENT", product.code());
        assertEquals("Horizon Prudent", product.name());
        assertEquals("Prudent", product.profile());
        assertEquals("0.020000", product.annualRate(),
                "annualRate voyage en chaine d'echelle 6 : un nombre JSON perdrait la forme "
                + "exacte du contrat gele");
        assertTrue(product.description() != null && !product.description().isBlank(),
                "la description est detenue par le catalogue et doit traverser le client");
    }

    @Test
    void leTauxAnnuelEstEgalementExposeEnPourcentage() {
        ProductDetail product = lookup.findProduct("DYNAMIQUE").orElseThrow();

        assertEquals("0.060000", product.annualRate());
        assertEquals("6.00", new BigDecimal(product.annualRate())
                        .multiply(new BigDecimal("100")).setScale(2).toPlainString(),
                "le pourcentage affiche reste derivable du taux du catalogue, sans arrondi "
                + "intermediaire");
    }

    @Test
    void consultationDeLaFicheDUnPlacementInconnu() {
        Optional<ProductDetail> product = lookup.findProduct("INEXISTANT");

        assertTrue(product.isEmpty(), "un 404 du catalogue est une absence de produit, jamais un "
                + "echec technique : startDraft repond 404 et non 503");
        assertEquals(1, stub.requests(), "aucun reessai sur un 404 (condition non rejouable)");
    }
}
