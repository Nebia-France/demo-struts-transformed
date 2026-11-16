package fr.demo.souscription.simulation.api;

import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Le garde de SimulationService.calculate est masqué par les contraintes déclaratives de
 * SimulationRequest : il n'est plus atteignable via HTTP. Le mapper est donc vérifié directement.
 */
class IllegalArgumentExceptionMapperTest {

    @Test
    void guardRejectionMapsToFourHundredProblemJsonWithTheExactDetail() {
        IllegalArgumentExceptionMapper mapper = new IllegalArgumentExceptionMapper();

        Response response = mapper.toResponse(
                new IllegalArgumentException("initialAmount hors bornes: 1000000 at fr.demo.X"));

        assertEquals(400, response.getStatus());
        assertEquals(ProblemJson.PROBLEM_JSON, response.getMediaType().toString());

        FieldErrors body = (FieldErrors) response.getEntity();
        assertArrayEquals("Paramètres de simulation invalides".getBytes(StandardCharsets.UTF_8),
                body.detail().getBytes(StandardCharsets.UTF_8),
                "le detail doit être identique à l'octet près au message legacy");
        assertEquals(400, body.status());
        assertEquals("about:blank", body.type());
        assertEquals("Validation failed", body.title());
        assertNull(body.errors(), "une rejection du garde ne porte pas d'erreurs par champ");
    }
}
