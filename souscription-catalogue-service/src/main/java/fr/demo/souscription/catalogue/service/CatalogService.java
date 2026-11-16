package fr.demo.souscription.catalogue.service;

import fr.demo.souscription.catalogue.domain.Product;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class CatalogService {

    private volatile List<Product> products = List.of();

    private volatile String version = "";

    void seed(List<Product> seeded) {
        this.products = List.copyOf(seeded);
    }

    void seedVersion(String computedAtSeeding) {
        this.version = computedAtSeeding;
    }

    /**
     * SHA-256 fingerprint of the seeded catalogue, computed once at seeding and reused as the
     * HTTP ETag (W6-T04). Never recomputed per request, so the value is stable across requests
     * and identical across replicas.
     */
    public String version() {
        return version;
    }

    public List<Product> getProducts() {
        return products;
    }

    public Optional<Product> find(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return products.stream().filter(p -> p.code().equals(code)).findFirst();
    }
}
