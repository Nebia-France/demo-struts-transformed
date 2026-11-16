package fr.demo.souscription.catalogue.service;

import fr.demo.souscription.catalogue.domain.Product;

import java.util.List;

/**
 * Pont de test donnant a la glue Cucumber un {@link CatalogService} amorce sans conteneur CDI
 * (W7-T02). Deux scenarios du catalogue n'ont aucune representation HTTP et restent au niveau
 * service : "Recherche d'un produit sans code transmis" et "La liste des produits n'est pas
 * modifiable". {@code seed} etant de portee paquet, le pont vit dans le paquet du service plutot
 * que de recourir a la reflexion.
 */
public final class CatalogueServiceBridge {

    private CatalogueServiceBridge() {
    }

    /** Service amorce avec les trois produits de reference Horizon. */
    public static CatalogService seeded() {
        CatalogService service = new CatalogService();
        service.seed(List.of(
                Product.of("PRUDENT", "Horizon Prudent", "Prudent", "Profil prudent", "0.02"),
                Product.of("EQUILIBRE", "Horizon Équilibre", "Équilibré", "Profil equilibre", "0.04"),
                Product.of("DYNAMIQUE", "Horizon Dynamique", "Dynamique", "Profil dynamique", "0.06")));
        return service;
    }
}
