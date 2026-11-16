package fr.demo.souscription.catalogue.domain;

// Parity test for 04-souscription-catalogue-domain-beans-parity.
// Plain JUnit 5, no Quarkus container: this is pure arithmetic on the record (test pyramid, plan 34).
//
// Source reference: src/main/java/fr/demo/souscription/model/Product.java
//   public Product(String code, String name, String profile, String description, String rate) {
//       this.annualRate = new BigDecimal(rate);
//   }
//   public BigDecimal getRatePercent() { return annualRate.multiply(new BigDecimal("100")); }

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductParityTest {

    @Test
    void leTauxAnnuelConserveLaValeurSourceAvecUneEchelleDe6() {
        Product product = Product.of("PRUDENT", "Horizon Prudent", "Prudent", "desc", "0.02");

        // numeric equality with the source value (0.02), scale strengthened to 6
        assertEquals(0, new BigDecimal("0.02").compareTo(product.annualRate()));
        assertEquals(6, product.annualRate().scale());
        assertEquals("0.020000", product.annualRate().toPlainString());
    }

    @Test
    void leTauxEnPourcentageRepliqueLaMultiplicationSource() {
        // source: getRatePercent() = annualRate.multiply(new BigDecimal("100"))
        assertEquals("6.00",
                Product.of("DYNAMIQUE", "Horizon Dynamique", "Dynamique", "desc", "0.06")
                        .ratePercent().toPlainString());
        assertEquals("2.00",
                Product.of("PRUDENT", "Horizon Prudent", "Prudent", "desc", "0.02")
                        .ratePercent().toPlainString());
        assertEquals("4.00",
                Product.of("EQUILIBRE", "Horizon Équilibre", "Équilibré", "desc", "0.04")
                        .ratePercent().toPlainString());
    }

    @Test
    void lesCinqComposantsSontPreserves() {
        Product product = Product.of("EQUILIBRE", "Horizon Équilibre", "Équilibré",
                "Une hypothèse intermédiaire pour construire un projet dans la durée.", "0.04");

        assertEquals("EQUILIBRE", product.code());
        assertEquals("Horizon Équilibre", product.name());
        assertEquals("Équilibré", product.profile());
        assertEquals("Une hypothèse intermédiaire pour construire un projet dans la durée.",
                product.description());
        assertEquals(0, new BigDecimal("0.04").compareTo(product.annualRate()));
    }

    @Test
    void uneEchelleIncompatibleEchoueBruyamment() {
        // RoundingMode.UNNECESSARY: a rate that cannot be represented at scale 6 must fail,
        // never be silently rounded (CFG-011 auditability).
        assertThrows(ArithmeticException.class,
                () -> Product.of("X", "X", "X", "X", "0.0200000001"));
    }
}
