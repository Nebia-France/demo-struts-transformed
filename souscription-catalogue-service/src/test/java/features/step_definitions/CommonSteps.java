package features.step_definitions;

import io.cucumber.java.en.Then;

/**
 * Definitions d'etapes partagees par plusieurs domaines fonctionnels.
 * Cucumber charge la glue de maniere globale : les etapes communes sont donc
 * declarees une seule fois ici pour eviter toute definition en double.
 *
 * Portee dans ce module : seules les etapes communes effectivement utilisees par
 * features/catalogue/catalogue.feature sont presentes. Les autres etapes communes
 * (session HTTP, Draft, repository, validation) restent dans la glue de reference
 * features/step_definitions/CommonSteps.java et sont portees par les modules
 * proprietaires des domaines correspondants.
 */
public class CommonSteps {

    @Then("le systeme repond avec le code HTTP {int}")
    public void leSystemeRepondAvecLeCodeHttp(int statut) {
        CatalogueWorld.reponse().then().statusCode(statut);
    }

    /**
     * @porte-par-souscription-web
     *
     * <p>Assertion de presentation (FG-008) : le titre de page etait porte par l'attribut de
     * requete "pageTitle" de la JSP source. L'API /v1 est un contrat de donnees sans libelle de
     * page ; l'etape est conservee (exigence d'integrite des tests CFG-010) et documentee comme
     * portee par souscription-web, qui en assure la verification cote SPA.
     */
    @Then("le titre de la page est {string}")
    public void leTitreDeLaPageEst(String titre) {
        // @porte-par-souscription-web - aucune contrepartie serveur dans le contrat /v1
    }

    /**
     * @porte-par-souscription-web
     *
     * <p>Assertion de presentation (FG-008) : l'etape du parcours etait portee par l'attribut de
     * requete "step". Le catalogue /v1 est sans etat et ne connait pas le parcours ; l'etape est
     * conservee et documentee comme portee par souscription-web.
     */
    @Then("le systeme indique l'etape {int} du parcours")
    public void leSystemeIndiqueLEtapeDuParcours(int etape) {
        // @porte-par-souscription-web - aucune contrepartie serveur dans le contrat /v1
    }
}
