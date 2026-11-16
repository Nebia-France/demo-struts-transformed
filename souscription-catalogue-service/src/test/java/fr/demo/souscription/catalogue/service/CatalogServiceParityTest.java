package fr.demo.souscription.catalogue.service;

// Parity-test skeleton for 04-souscription-catalogue-domain-beans-parity.
// Copy to souscription-catalogue-service/src/test/java/fr/demo/souscription/catalogue/service/.
// @QuarkusTest because it exercises the CDI bean seeded by StartupEvent (plan 05).
//
// Source reference: src/main/java/fr/demo/souscription/service/CatalogService.java
//   public Optional<Product> find(String code) {
//       return products.stream().filter(product -> product.getCode().equals(code)).findFirst();
//   }
// 'equals' => case- and whitespace-sensitive. Gherkin: "Code produit vide ou mal formate".

import fr.demo.souscription.catalogue.domain.Product;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CatalogServiceParityTest {

    @Inject
    CatalogService catalogService;

    // The StartupEvent observer that seeds the catalogue is introduced by plan 05; until then
    // the test seeds the bean with the three source products of
    // src/main/java/fr/demo/souscription/service/CatalogService.java.
    @BeforeEach
    void seedCatalogue() {
        catalogService.seed(List.of(
                Product.of("PRUDENT", "Horizon Prudent", "Prudent",
                        "Une hypothèse de progression modérée pour découvrir le fonctionnement de l'épargne.",
                        "0.02"),
                Product.of("EQUILIBRE", "Horizon Équilibre", "Équilibré",
                        "Une hypothèse intermédiaire pour construire un projet dans la durée.", "0.04"),
                Product.of("DYNAMIQUE", "Horizon Dynamique", "Dynamique",
                        "Une hypothèse de progression plus élevée, associée à un profil de risque supérieur.",
                        "0.06")));
    }

    @Test
    void laRechercheEstSensibleALaCasseEtAuxEspaces() {
        assertTrue(catalogService.find("PRUDENT").isPresent());
        assertTrue(catalogService.find("prudent").isEmpty());
        assertTrue(catalogService.find(" PRUDENT ").isEmpty());
        assertTrue(catalogService.find("PRUDENT123").isEmpty());
        assertTrue(catalogService.find("").isEmpty());
        assertTrue(catalogService.find("INEXISTANT").isEmpty());
    }

    @Test
    void laRechercheSansCodeNeLeveAucuneException() {
        // Gherkin: "Recherche d'un produit sans code transmis"
        assertTrue(catalogService.find(null).isEmpty());
    }

    @Test
    void lesTroisCodesSourceSontPresents() {
        assertEquals(3, catalogService.getProducts().size());
        assertTrue(catalogService.find("PRUDENT").isPresent());
        assertTrue(catalogService.find("EQUILIBRE").isPresent());
        assertTrue(catalogService.find("DYNAMIQUE").isPresent());
    }
}
