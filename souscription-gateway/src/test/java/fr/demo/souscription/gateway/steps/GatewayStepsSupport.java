package fr.demo.souscription.gateway.steps;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Shared REST Assured support for the gateway Cucumber glue.
 *
 * <p>Holds the HTTP port of the packaged gateway under test ({@code gateway.cucumber.port}) and the
 * {@link Response} shared between the steps of a scenario. Glue classes added by plans 07 to 33
 * extend this class so they all target the same base URI and share the same last response.
 */
public abstract class GatewayStepsSupport {

    /**
     * Port of the packaged gateway driven by the Cucumber gate. The {@code @QuarkusTest} suite binds
     * a random port ({@code quarkus.http.test-port=0}), so the gate has its own fixed port,
     * overridable with {@code -Dgateway.cucumber.port}.
     */
    private static final int DEFAULT_TEST_PORT = 18581;

    /**
     * Shared across glue classes: Cucumber instantiates one object per glue class per scenario, so
     * a scenario whose {@code When} and {@code Then} live in two different classes (for instance
     * {@code CatalogueSteps} then {@code SecuriteWebSteps}) must see the same response. Every
     * scenario records a response before asserting on it.
     */
    private static Response lastResponse;

    protected GatewayStepsSupport() {
        RestAssured.port = testPort();
    }

    protected static int testPort() {
        return ConfigProvider.getConfig()
                .getOptionalValue("gateway.cucumber.port", Integer.class)
                .orElse(DEFAULT_TEST_PORT);
    }

    protected RequestSpecification request() {
        return RestAssured.given();
    }

    protected Response lastResponse() {
        if (lastResponse == null) {
            throw new IllegalStateException("No HTTP response recorded yet for this scenario");
        }
        return lastResponse;
    }

    protected void recordResponse(Response response) {
        GatewayStepsSupport.lastResponse = response;
    }
}
