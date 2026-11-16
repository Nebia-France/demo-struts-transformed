package fr.demo.souscription.catalogue.api;

import fr.demo.souscription.catalogue.domain.Product;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(name = "ProductDetail")
public record ProductDetail(@Schema(required = true) String code,
                            @Schema(required = true) String name,
                            @Schema(required = true) String profile,
                            @Schema(required = true) String description,
                            @Schema(required = true,
                                    description = "Taux annuel, DECIMAL(8,6) : 6 decimales exactes, notation non scientifique",
                                    examples = "0.020000", multipleOf = 0.000001)
                            BigDecimal annualRate,     // DECIMAL(8,6)
                            // Not in the reference contracts: additional optional field, which is
                            // the only backward-compatible kind of addition within /v1 (plan 37).
                            @Schema(description = "Taux en pourcentage, 2 decimales exactes", examples = "2.00")
                            BigDecimal ratePercent) {  // 2 decimals

    public static ProductDetail from(Product product) {
        return new ProductDetail(product.code(), product.name(), product.profile(),
                                 product.description(), product.annualRate(),
                                 product.ratePercent());
    }
}
