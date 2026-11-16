package fr.demo.souscription.simulation.service;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.demo.souscription.simulation.model.Simulation;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Verrou du contrat d'arithmétique décimale (W3-T01).
 *
 * <p>Les valeurs de référence sont celles produites par l'implémentation Java 8 historique
 * ({@code fr.demo.souscription.service.SimulationService}) : boucle mensuelle en
 * MathContext.DECIMAL128, arrondi final HALF_UP à l'échelle 2. Toute réécriture de la boucle
 * (stream, {@code pow}, réordonnancement, changement de MathContext) casse ce test.
 */
@QuarkusTest
class DecimalContractTest {

    @Inject
    SimulationService service;

    @Inject
    ObjectMapper objectMapper;

    @ParameterizedTest
    @CsvSource({
        "100,0,1,0.02,100.00,0.00,100.00,102.02",
        "1000,100,5,0.04,1000.00,100.00,7000.00,7850.89",
        "100000,2000,30,0.06,100000.00,2000.00,820000.00,2611287.61",
        "100,2000,1,0.06,100.00,2000.00,24100.00,24777.29"
    })
    void matchesTheLegacyReferenceValuesExactly(String initial, String monthly, int years, String rate,
            String expectedInitial, String expectedMonthly, String expectedContributed,
            String expectedFinalCapital) {
        Simulation result = service.calculate(new BigDecimal(initial), new BigDecimal(monthly),
                years, new BigDecimal(rate));

        // assertEquals sur BigDecimal compare valeur ET échelle.
        assertEquals(new BigDecimal(expectedInitial), result.initial());
        assertEquals(new BigDecimal(expectedMonthly), result.monthly());
        assertEquals(years, result.years());
        assertEquals(new BigDecimal(expectedContributed), result.contributed());
        assertEquals(new BigDecimal(expectedFinalCapital), result.finalCapital());

        assertEquals(2, result.initial().scale());
        assertEquals(2, result.monthly().scale());
        assertEquals(2, result.contributed().scale());
        assertEquals(2, result.finalCapital().scale());
    }

    @Test
    void objectMapperNeverWritesBigDecimalInScientificNotation() throws Exception {
        // 8.2E+5 serait accepté par JSON mais casserait le contrat de wire.
        String json = objectMapper.writeValueAsString(new BigDecimal("8.2E+5"));
        assertFalse(json.contains("E") || json.contains("e"), json);
        assertEquals("820000", json);
    }

    @Test
    void serialisesMonetaryFieldsInPlainNotation() {
        String body = given().contentType(ContentType.JSON)
                .body("{\"initialAmount\":100000.00,\"monthlyAmount\":2000.00,"
                        + "\"durationYears\":30,\"annualRate\":0.060000}")
            .when().post("/v1/simulations")
            .then().statusCode(200)
                .contentType(ContentType.JSON)
                .extract().asString();

        assertFalse(body.contains("E+") || body.contains("e+") || body.contains("E-")
                        || body.contains("e-"),
                "La réponse ne doit contenir aucune notation scientifique : " + body);
        assertEquals(true, body.contains("100000.00"), body);
        assertEquals(true, body.contains("2000.00"), body);
        assertEquals(true, body.contains("820000.00"), body);
        assertEquals(true, body.contains("2611287.61"), body);
    }
}
