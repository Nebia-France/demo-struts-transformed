package fr.demo.souscription.catalogue.api;

import fr.demo.souscription.catalogue.domain.Product;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(name = "ProductSummary", description = "Produit du catalogue, vue liste")
public record ProductSummary(
    @Schema(required = true, examples = "PRUDENT") String code,
    @Schema(required = true, examples = "Horizon Prudent") String name,
    @Schema(required = true, examples = "Prudent") String profile,
    @Schema(required = true, examples = "0.020000", multipleOf = 0.000001,
            description = "Taux annuel, DECIMAL(8,6) : 6 decimales exactes, notation non scientifique")
    BigDecimal annualRate) {

    public static ProductSummary from(Product product) {
        return new ProductSummary(product.code(), product.name(),
                                  product.profile(), product.annualRate());
    }
}
