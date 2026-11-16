package fr.demo.souscription.catalogue.service;

import fr.demo.souscription.catalogue.domain.Product;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CatalogServiceTest {

    @Inject
    CatalogService catalogService;

    @Test
    void leCatalogueEstAmorceAvecTroisProduits() {
        assertEquals(3, catalogService.getProducts().size());
        assertTrue(catalogService.find("PRUDENT").isPresent());
        assertTrue(catalogService.find("INEXISTANT").isEmpty());
        assertTrue(catalogService.find(null).isEmpty());
    }

    @Test
    void laListeDesProduitsNEstPasModifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> catalogService.getProducts().add(
                        Product.of("X", "X", "X", "X", "0.01")));
    }
}
