package fr.demo.souscription.catalogue.service;

// AG-015: the catalogue is read concurrently by souscription-gateway and souscription-service.
// In the source it was an in-process, single-request read of an unmodifiableList. The parity
// criterion is the absence of shared mutable state: concurrent readers must always observe the
// same immutable 3-product list.

import fr.demo.souscription.catalogue.domain.Product;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class ConcurrentReadParityTest {

    @Inject
    CatalogService catalogService;

    @Test
    void centLecturesConcurrentesObserventLeMemeCatalogue() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<String>> reads = IntStream.range(0, 100)
                    .<Callable<String>>mapToObj(i -> () -> catalogService.getProducts().stream()
                            .map(Product::code).collect(Collectors.joining(",")))
                    .toList();

            Set<String> observed = pool.invokeAll(reads).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).collect(Collectors.toSet());

            assertEquals(Set.of("PRUDENT,EQUILIBRE,DYNAMIQUE"), observed,
                    "les lecteurs concurrents observent des etats differents");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aucunLecteurNePeutMuterLEtatPartage() {
        List<Product> products = catalogService.getProducts();
        Product extra = Product.of("X", "X", "X", "X", "0.01");

        assertThrows(UnsupportedOperationException.class, () -> products.add(extra));
        assertThrows(UnsupportedOperationException.class, () -> products.set(0, extra));
        assertEquals(3, catalogService.getProducts().size());
    }

    @Test
    void lesLecturesConcurrentesParCodeSontCoherentes() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = pool.invokeAll(IntStream.range(0, 64)
                    .<Callable<Boolean>>mapToObj(i -> () ->
                            catalogService.find("DYNAMIQUE").isPresent()
                                    && catalogService.find("inexistant").isEmpty())
                    .toList());
            for (Future<Boolean> result : results) {
                assertEquals(Boolean.TRUE, result.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
