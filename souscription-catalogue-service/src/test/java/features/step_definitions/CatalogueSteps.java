package features.step_definitions;

import fr.demo.souscription.catalogue.domain.Product;
import fr.demo.souscription.catalogue.service.CatalogService;
import fr.demo.souscription.catalogue.service.CatalogueServiceBridge;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.math.BigDecimal;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Etapes du domaine "Catalogue des placements", recablees sur l'API REST /v1 (W7-T02).
 *
 * <p>Origine : {@code fr.demo.souscription.service.CatalogService},
 * {@code fr.demo.souscription.action.CatalogAction}, {@code fr.demo.souscription.model.Product}.
 * Les actions Struts sont remplacees par {@code GET /v1/products} et
 * {@code GET /v1/products/{code}} ; le cycle de vie du serveur et l'URI de base sont portes par
 * {@link CatalogueWorld} (aucun port code en dur).
 *
 * <p>Deux scenarios restent au niveau service, faute de representation HTTP : la recherche sans
 * code transmis et l'immuabilite de la liste retournee. Ils passent par
 * {@link CatalogueServiceBridge}.
 *
 * <p>Deux etapes de presentation du scenario "Consultation de la liste des placements" sont
 * conservees (CFG-010, aucune suppression) et portees par souscription-web, tag
 * {@code @porte-par-souscription-web} :
 * <ul>
 *   <li>{@code le systeme indique l'etape 1 du parcours}</li>
 *   <li>{@code le titre de la page est "Catalogue des placements"}</li>
 * </ul>
 * Elles n'ont aucune contrepartie dans le contrat /v1 (FG-008) et sont implementees sans
 * assertion serveur dans {@link CommonSteps}, de maniere a ne pas forcer des donnees de
 * presentation dans l'API.
 */
public class CatalogueSteps {

    private CatalogService catalogService;

    private Optional<Product> resultatDeRecherche;

    @Given("le catalogue Horizon est initialise avec les produits de reference")
    public void leCatalogueHorizonEstInitialiseAvecLesProduitsDeReference() {
        catalogService = CatalogueServiceBridge.seeded();
        given().when().get("/v1/products").then().statusCode(200).body("size()", is(3));
    }

    @When("l'utilisateur demande la page du catalogue")
    public void lUtilisateurDemandeLaPageDuCatalogue() {
        CatalogueWorld.lastResponse = given().when().get("/v1/products");
    }

    @When("l'utilisateur demande la fiche du produit de code {string}")
    public void lUtilisateurDemandeLaFicheDuProduitDeCode(String code) {
        // Encodage explicite et encodage automatique desactive : REST Assured normalise les
        // espaces d'un parametre de chemin, ce qui masquerait le cas limite " PRUDENT ".
        String chemin = "/v1/products/" + code.replace(" ", "%20");
        CatalogueWorld.lastResponse = given().urlEncodingEnabled(false).when().get(chemin);
    }

    @When("l'utilisateur recherche un produit sans fournir de code")
    public void lUtilisateurRechercheUnProduitSansFournirDeCode() {
        resultatDeRecherche = catalogService.find(null);
    }

    @When("l'utilisateur soumet le catalogue en methode POST")
    public void lUtilisateurSoumetLeCatalogueEnMethodePost() {
        CatalogueWorld.lastResponse = given().when().post("/v1/products");
    }

    @When("le systeme tente d'ajouter un produit a la liste retournee par le catalogue")
    public void leSystemeTenteDAjouterUnProduitALaListeRetourneeParLeCatalogue() {
        try {
            catalogService.getProducts().add(Product.of("X", "X", "X", "X", "0.01"));
        } catch (RuntimeException e) {
            CatalogueWorld.lastException = e;
        }
    }

    @Then("le systeme affiche les {int} placements du catalogue")
    public void leSystemeAfficheLesPlacementsDuCatalogue(int nombre) {
        CatalogueWorld.reponse().then().statusCode(200)
                .body("size()", is(nombre));
    }

    @Then("le systeme affiche la fiche du produit nomme {string}")
    public void leSystemeAfficheLaFicheDuProduitNomme(String nom) {
        CatalogueWorld.reponse().then().statusCode(200)
                .body("name", equalTo(nom));
    }

    @Then("le profil affiche est {string}")
    public void leProfilAfficheEst(String profil) {
        CatalogueWorld.reponse().then().statusCode(200)
                .body("profile", equalTo(profil));
    }

    @Then("le taux annuel affiche est {string}")
    public void leTauxAnnuelAfficheEst(String attendu) {
        // Comparaison numerique obligatoire : la table d'exemples ecrit 0.02 alors que le contrat
        // de cable impose 6 decimales (0.020000). Une comparaison de chaines ferait echouer le
        // build pour une difference de representation, pas de valeur.
        assertEquals(0, new BigDecimal(attendu).compareTo(new BigDecimal(valeur("annualRate"))),
                "taux annuel attendu " + attendu + ", obtenu " + valeur("annualRate"));
    }

    @Then("le taux affiche en pourcentage est {string}")
    public void leTauxAfficheEnPourcentageEst(String attendu) {
        assertEquals(0, new BigDecimal(attendu).compareTo(new BigDecimal(valeur("ratePercent"))),
                "taux en pourcentage attendu " + attendu + ", obtenu " + valeur("ratePercent"));
    }

    @Then("aucun produit n'est retourne par le catalogue")
    public void aucunProduitNEstRetourneParLeCatalogue() {
        assertNotNull(resultatDeRecherche, "la recherche de niveau service n'a pas ete executee");
        assertTrue(resultatDeRecherche.isEmpty(), "un produit a ete retourne sans code transmis");
    }

    @Then("une erreur de modification interdite est levee")
    public void uneErreurDeModificationInterditeEstLevee() {
        assertNotNull(CatalogueWorld.lastException, "aucune exception n'a ete levee");
        assertEquals(UnsupportedOperationException.class, CatalogueWorld.lastException.getClass());
    }

    private String valeur(String champ) {
        CatalogueWorld.reponse().then().statusCode(200);
        Object brut = CatalogueWorld.reponse().then().extract().path(champ);
        assertNotNull(brut, "champ absent de la reponse : " + champ);
        return brut.toString();
    }
}
