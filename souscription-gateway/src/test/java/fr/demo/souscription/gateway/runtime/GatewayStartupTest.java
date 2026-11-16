package fr.demo.souscription.gateway.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GatewayStartupTest {

    @Inject
    GatewayConfig config;

    @Test
    void configIsInjectable() {
        assertNotNull(config);
        assertEquals(100, config.csrf().maxTokenLength());
    }
}
