package fr.demo.souscription.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.demo.souscription.api.dto.ProductDetail;
import fr.demo.souscription.contract.ContractStub.Call;
import fr.demo.souscription.contract.ContractStub.Reply;
import fr.demo.souscription.integration.CatalogueLookup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Scenarios consommes du catalogue rejoues contre un bouchon genere depuis le contrat gele
 * {@code global/contracts/souscription-service-to-souscription-catalogue-service/openapi.yaml}
 * (plan 42, etape 3).
 *
 * <p>Chaque scenario echoue si le client emet une requete hors contrat ou si la reponse servie
 * s'ecarte du schema {@code shared-schemas/ProductDetail.json} / {@code FieldErrors.json} : le
 * bouchon controle le fil dans les deux sens.
 */
@QuarkusTest
@TestProfile(ContractDrivenStubsProfile.class)
class CatalogueContractStubTest {

    static final Path CONTRACT = ContractDocuments.contract(
            "souscription-service-to-souscription-catalogue-service", "openapi.yaml");

    /** Semantique fournisseur : les produits servis. Le contrat n'en porte que la forme. */
    private static final Map<String, String> PRODUCTS = Map.of(
            "PRUDENT", """
                    {"code":"PRUDENT","name":"Horizon Prudent","profile":"Prudent",\
                    "description":"Une hypothese de progression moderee.","annualRate":"0.020000"}""",
            "DYNAMIQUE", """
                    {"code":"DYNAMIQUE","name":"Horizon Dynamique","profile":"Dynamique",\
                    "description":"Une hypothese de progression plus elevee.",\
                    "annualRate":"0.060000"}""");

    private static ContractStub stub;

    @Inject
    CatalogueLookup lookup;

    @BeforeAll
    static void startStub() throws IOException {
        stub = new ContractStub(CONTRACT, ContractDrivenStubsProfile.CATALOGUE_PORT,
                CatalogueContractStubTest::provider);
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @BeforeEach
    void reset() {
        stub.reset();
    }

    @AfterEach
    void theWireConformedToTheContract() {
        assertEquals(List.of(), stub.violations(), "ecarts au contrat catalogue");
        assertEquals(List.of(), stub.requestViolations(), "requetes non conformes au contrat");
    }

    private static Reply provider(Call call) {
        String product = PRODUCTS.get(call.pathParameters().get("code"));
        return product != null ? new Reply(200, product) : new Reply(404, """
                {"type":"urn:horizon:not-found","title":"horizon.request.not-found","status":404}""");
    }

    @Test
    void theStubRoutesExactlyTheConsumedOperation() {
        assertEquals(List.of("getProduct"), stub.operationIds());
    }

    /** Scenario « Consultation de la fiche d'un placement existant ». */
    @Test
    void consultationDeLaFicheDUnPlacementExistant() {
        ProductDetail product = lookup.findProduct("PRUDENT").orElseThrow();

        assertEquals("PRUDENT", product.code());
        assertEquals("Horizon Prudent", product.name());
        assertEquals("Prudent", product.profile());
        assertEquals("0.020000", product.annualRate());
        assertTrue(product.description() != null && !product.description().isBlank());
        assertEquals(1, stub.requests());
    }

    /** Scenario « Le taux annuel est egalement expose en pourcentage ». */
    @Test
    void leTauxAnnuelEstEgalementExposeEnPourcentage() {
        ProductDetail product = lookup.findProduct("DYNAMIQUE").orElseThrow();

        assertEquals("0.060000", product.annualRate());
        assertEquals("6.00", new BigDecimal(product.annualRate())
                .multiply(new BigDecimal("100")).setScale(2).toPlainString());
    }

    /** Scenario « Consultation de la fiche d'un placement inconnu ». */
    @Test
    void consultationDeLaFicheDUnPlacementInconnu() {
        Optional<ProductDetail> product = lookup.findProduct("INEXISTANT");

        assertTrue(product.isEmpty(), "un 404 du contrat est une absence de produit");
        assertEquals(1, stub.requests(), "aucun reessai sur un 404");
    }
}
