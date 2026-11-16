package fr.demo.souscription.domain;

import java.math.BigDecimal;

/**
 * Projection en lecture du produit, detenu par souscription-catalogue-service.
 *
 * <p>Alimentee par le client REST de la vague 6 : ce type n'est volontairement pas une entite
 * JPA, le service de souscription ne possede pas le catalogue. Le taux reste un
 * {@link BigDecimal} d'echelle 6, jamais un {@code double}.
 */
public record Product(String code, String name, String profile, BigDecimal annualRate) {
}
