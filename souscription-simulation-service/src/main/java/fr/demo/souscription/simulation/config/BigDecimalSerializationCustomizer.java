package fr.demo.souscription.simulation.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Verrou de sérialisation du contrat décimal : tout BigDecimal est écrit en notation
 * simple (820000.00) et jamais en notation scientifique (8.2E+5).
 *
 * <p>Quarkus 3.20 n'expose pas {@code WRITE_BIGDECIMAL_AS_PLAIN} comme propriété native ;
 * la clé {@code quarkus.jackson.write-bigdecimal-as-plain} est donc déclarée ici et
 * appliquée à l'ObjectMapper. Vérifié par DecimalContractTest.
 */
@Singleton
public class BigDecimalSerializationCustomizer implements ObjectMapperCustomizer {

    private final boolean writeBigDecimalAsPlain;

    public BigDecimalSerializationCustomizer(
            @ConfigProperty(name = "quarkus.jackson.write-bigdecimal-as-plain", defaultValue = "true")
            boolean writeBigDecimalAsPlain) {
        this.writeBigDecimalAsPlain = writeBigDecimalAsPlain;
    }

    @Override
    public void customize(ObjectMapper objectMapper) {
        objectMapper.configure(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN, writeBigDecimalAsPlain);
        // Symétrique en lecture : une durée fractionnaire (5.5) doit être refusée, comme le
        // faisait le regex legacy "(?:[1-9]|[12][0-9]|30)". Jackson active ACCEPT_FLOAT_AS_INT
        // par défaut et tronquerait silencieusement 5.5 en 5, acceptant une saisie que le
        // monolithe rejetait. Vérifié par SimulationValidationIT.
        objectMapper.configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, false);
    }
}
