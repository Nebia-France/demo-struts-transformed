package fr.demo.souscription.catalogue.api;

// Parity-test skeleton for 07-souscription-catalogue-read-dtos-parity.
// Plain JUnit 5 — pure projection logic, no container needed (test pyramid, plan 34).
//
// Source reference: catalogue.jsp rendered code / name / profile / rate;
// produit.jsp (and SubscriptionIT lines 121, 134) additionally rendered description and rate %.

import fr.demo.souscription.catalogue.domain.Product;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductDtoParityTest {

    private static final Product EQUILIBRE = Product.of("EQUILIBRE", "Horizon Équilibre", "Équilibré",
            "Une hypothèse intermédiaire pour construire un projet dans la durée.", "0.04");

    private static List<String> componentsOf(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    @Test
    void laVueListePorteExactementLesQuatreChampsDeLaListeSource() {
        assertEquals(List.of("code", "name", "profile", "annualRate"),
                componentsOf(ProductSummary.class));
    }

    @Test
    void laVueDetailPorteLesSixChampsDeLaFicheProduitSource() {
        assertEquals(List.of("code", "name", "profile", "description", "annualRate", "ratePercent"),
                componentsOf(ProductDetail.class));
    }

    @Test
    void laProjectionListeRecopieLesValeursDuDomaine() {
        ProductSummary summary = ProductSummary.from(EQUILIBRE);

        assertEquals("EQUILIBRE", summary.code());
        assertEquals("Horizon Équilibre", summary.name());
        assertEquals("Équilibré", summary.profile());
        assertEquals(EQUILIBRE.annualRate(), summary.annualRate());          // value AND scale
        assertEquals(6, summary.annualRate().scale());
    }

    @Test
    void laProjectionDetailRecopieLesValeursDuDomaineEtLePourcentage() {
        ProductDetail detail = ProductDetail.from(EQUILIBRE);

        assertEquals(EQUILIBRE.description(), detail.description());
        assertEquals(EQUILIBRE.annualRate(), detail.annualRate());
        assertEquals(EQUILIBRE.ratePercent(), detail.ratePercent());
        assertEquals("4.00", detail.ratePercent().toPlainString());
        assertEquals("0.040000", detail.annualRate().toPlainString());       // no normalisation
    }
}
