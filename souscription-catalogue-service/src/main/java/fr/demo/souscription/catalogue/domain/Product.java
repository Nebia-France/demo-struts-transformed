package fr.demo.souscription.catalogue.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record Product(String code, String name, String profile,
                      String description, BigDecimal annualRate) {

    public static Product of(String code, String name, String profile,
                             String description, String rate) {
        return new Product(code, name, profile, description,
                           new BigDecimal(rate).setScale(6, RoundingMode.UNNECESSARY));
    }

    public BigDecimal ratePercent() {
        return annualRate.multiply(new BigDecimal("100")).setScale(2, RoundingMode.UNNECESSARY);
    }
}
