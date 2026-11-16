package fr.demo.souscription.catalogue.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

/**
 * Locks the wire representation of BigDecimal: WRITE_BIGDECIMAL_AS_PLAIN forbids scientific
 * notation (Jackson can otherwise emit 6E+0) and withExactBigDecimals(true) prevents scale
 * normalisation (otherwise 0.020000 collapses to 0.02). CFG-007 / CFG-011.
 */
@Singleton
public class JacksonCustomizer implements ObjectMapperCustomizer {

    @Override
    public void customize(ObjectMapper mapper) {
        mapper.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
        mapper.disable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    }
}
