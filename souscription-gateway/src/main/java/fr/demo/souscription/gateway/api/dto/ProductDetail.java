package fr.demo.souscription.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.math.BigDecimal;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Full product definition including its marketing description, mirroring
 * {@code global/contracts/shared-schemas/ProductDetail.json}. Owner of the authoritative
 * {@code annualRate}.
 */
// Le catalogue publie aussi ce champ optionnel ; il ne fait pas partie de la projection publique.
@JsonIgnoreProperties("ratePercent")
public record ProductDetail(String code, String name, String profile, String description,
                            @Schema(type = SchemaType.STRING, pattern = "^[0-9]\\.[0-9]{6}$",
                                    examples = "0.020000")
                            @JsonSerialize(using = ScaledDecimalSerializer.Rate.class)
                            BigDecimal annualRate) {
}
