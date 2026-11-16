package fr.demo.souscription.gateway.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.math.BigDecimal;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Catalogue list projection of a savings product, mirroring
 * {@code global/contracts/shared-schemas/ProductSummary.json}.
 *
 * <p>{@code annualRate} travels as a JSON string with exactly six decimals
 * ({@code "0.020000"} = 2%).
 */
public record ProductSummary(String code, String name, String profile,
                             @Schema(type = SchemaType.STRING, pattern = "^[0-9]\\.[0-9]{6}$",
                                     examples = "0.020000")
                             @JsonSerialize(using = ScaledDecimalSerializer.Rate.class)
                             BigDecimal annualRate) {

    /** Projection without the commercial profile, used where only the rate matters. */
    public ProductSummary(String code, String name, BigDecimal annualRate) {
        this(code, name, null, annualRate);
    }
}
