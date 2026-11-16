package fr.demo.souscription.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO produit complet, aligne sur le schema gele ProductDetail.json.
 *
 * <p>{@code annualRate} est une chaine d'echelle 6 ({@code ^[0-9]\.[0-9]{6}$}) : le taux ne
 * voyage pas en nombre JSON. {@code description} est detenu par le catalogue et n'est pas copie
 * dans le brouillon persiste.
 */
// Champ optionnel du fournisseur, absent de la projection du service de souscription.
@JsonIgnoreProperties("ratePercent")
public record ProductDetail(String code, String name, String profile, String description,
                            String annualRate) {
}
