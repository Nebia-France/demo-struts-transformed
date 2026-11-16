package fr.demo.souscription.api.dto;

/**
 * Projection produit denormalisee portee par la souscription confirmee, alignee sur le schema
 * gele ProductSummary.json.
 *
 * <p>{@code annualRate} est une chaine d'echelle 6 ({@code ^[0-9]\.[0-9]{6}$}). La description
 * du produit n'en fait pas partie : une lecture de souscription n'appelle jamais le catalogue.
 */
public record ProductSummary(String code, String name, String profile, String annualRate) {
}
