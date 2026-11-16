package fr.demo.souscription.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fr.demo.souscription.gateway.api.dto.ProductDetail;
import fr.demo.souscription.gateway.api.dto.ProductSummary;
import io.quarkus.test.junit.QuarkusTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

/**
 * Path, status and DTO shape of {@link CatalogueClient} against the WireMock double of
 * {@code souscription-gateway-to-souscription-catalogue-service} (W4-T01).
 */
@QuarkusTest
class CatalogueClientTest {

    @RestClient
    CatalogueClient catalogue;

    @Test
    void listCallsTheContractPathAndDeserialisesTheSummaries() {
        List<ProductSummary> products = catalogue.list().await().atMost(Duration.ofSeconds(5));

        assertEquals(3, products.size());
        assertEquals(List.of("PRUDENT", "EQUILIBRE", "DYNAMIQUE"),
                products.stream().map(ProductSummary::code).toList());
        assertEquals(0, new BigDecimal("0.020000").compareTo(products.get(0).annualRate()));
        DownstreamWireMocks.server("catalogue")
                .verify(getRequestedFor(urlPathEqualTo("/v1/products")));
    }

    @Test
    void detailCallsTheContractPathAndDeserialisesTheDetail() {
        ProductDetail product = catalogue.detail("EQUILIBRE").await().atMost(Duration.ofSeconds(5));

        assertEquals("EQUILIBRE", product.code());
        assertEquals("Horizon \u00c9quilibre", product.name());
        assertEquals(0, new BigDecimal("0.040000").compareTo(product.annualRate()));
        DownstreamWireMocks.server("catalogue")
                .verify(getRequestedFor(urlPathEqualTo("/v1/products/EQUILIBRE")));
    }

    @Test
    void anUnknownCodeFailsWithDownstreamNotFound() {
        assertThrows(DownstreamNotFoundException.class,
                () -> catalogue.detail("INCONNU").await().atMost(Duration.ofSeconds(5)));
    }
}
