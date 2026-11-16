package fr.demo.souscription.catalogue.service;

// Parity-test skeleton for 05-souscription-catalogue-startup-seed-immutability-parity.
// Copy to souscription-catalogue-service/src/test/java/fr/demo/souscription/catalogue/service/.
//
// Source reference: src/main/java/fr/demo/souscription/service/CatalogService.java
//   private final List<Product> products = Collections.unmodifiableList(Arrays.asList(
//       new Product("PRUDENT",   "Horizon Prudent",   "Prudent",   "...", "0.02"),
//       new Product("EQUILIBRE", "Horizon Équilibre", "Équilibré", "...", "0.04"),
//       new Product("DYNAMIQUE", "Horizon Dynamique", "Dynamique", "...", "0.06")));
// Gherkin: "La liste des produits n'est pas modifiable".

import fr.demo.souscription.catalogue.domain.Product;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class CatalogueSeedParityTest {

    @Inject
    CatalogService catalogService;

    @Test
    void lesTroisProduitsSontAmorcesDansLOrdreSource() {
        List<Product> products = catalogService.getProducts();
        assertEquals(3, products.size());
        assertEquals(List.of("PRUDENT", "EQUILIBRE", "DYNAMIQUE"),
                products.stream().map(Product::code).toList());
    }

    @Test
    void lesLibellesAccentuesSontIdentiquesALaSource() {
        Product equilibre = catalogService.find("EQUILIBRE").orElseThrow();
        assertEquals("Horizon Équilibre", equilibre.name());
        assertEquals("Équilibré", equilibre.profile());
        assertEquals("Une hypothèse intermédiaire pour construire un projet dans la durée.",
                equilibre.description());
    }

    @Test
    void lesTauxSontIdentiquesALaSource() {
        assertEquals(0, new BigDecimal("0.02")
                .compareTo(catalogService.find("PRUDENT").orElseThrow().annualRate()));
        assertEquals(0, new BigDecimal("0.04")
                .compareTo(catalogService.find("EQUILIBRE").orElseThrow().annualRate()));
        assertEquals(0, new BigDecimal("0.06")
                .compareTo(catalogService.find("DYNAMIQUE").orElseThrow().annualRate()));

        assertEquals("6.00",
                catalogService.find("DYNAMIQUE").orElseThrow().ratePercent().toPlainString());
    }

    @Test
    void laListeRetourneeNEstPasModifiable() {
        List<Product> products = catalogService.getProducts();
        Product extra = Product.of("X", "X", "X", "X", "0.01");

        assertThrows(UnsupportedOperationException.class, () -> products.add(extra));
        assertThrows(UnsupportedOperationException.class, () -> products.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> products.set(0, extra));
    }

    @Test
    void laListeEstCopieeEtNonEnveloppee() {
        // List.copyOf, not unmodifiableList(Arrays.asList(...)): mutating the caller's list
        // afterwards must not be observable through getProducts().
        // A standalone instance is used so the injected application-scoped bean keeps the
        // start-up seed for the other tests.
        CatalogService isolated = new CatalogService();
        List<Product> mutable = new ArrayList<>(List.of(Product.of("A", "A", "A", "A", "0.01")));
        isolated.seed(mutable);

        mutable.add(Product.of("B", "B", "B", "B", "0.02"));
        mutable.clear();

        assertEquals(1, isolated.getProducts().size());
        assertEquals("A", isolated.getProducts().get(0).code());
        assertThrows(UnsupportedOperationException.class,
                () -> isolated.getProducts().set(0, Product.of("C", "C", "C", "C", "0.03")));

        assertEquals(3, catalogService.getProducts().size());
    }
}
