package fr.demo.souscription.gateway.api.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Serialises a {@link BigDecimal} as a JSON <em>string</em> with a fixed number of decimals, as
 * required by {@code global/contracts/shared-schemas/*.json}.
 *
 * <p>A JSON number would be read by the SPA as an IEEE-754 binary value, where neither
 * {@code 0.02} nor a two-decimal amount is exactly representable; a string preserves the scales of
 * {@code src/main/resources/db/schema.sql} (amounts {@code DECIMAL(14,2)}, annual rate
 * {@code DECIMAL(8,6)}) byte for byte.
 */
public abstract class ScaledDecimalSerializer extends JsonSerializer<BigDecimal> {

    private final int scale;

    ScaledDecimalSerializer(int scale) {
        this.scale = scale;
    }

    @Override
    public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider provider)
            throws IOException {
        generator.writeString(value.setScale(scale, RoundingMode.HALF_UP).toPlainString());
    }

    /** Monetary amount, {@code DECIMAL(14,2)}. */
    public static final class Amount extends ScaledDecimalSerializer {

        public Amount() {
            super(2);
        }
    }

    /** Annual rate expressed as a decimal fraction, {@code DECIMAL(8,6)}. */
    public static final class Rate extends ScaledDecimalSerializer {

        public Rate() {
            super(6);
        }
    }
}
