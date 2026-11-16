@domaine-fonctionnel @catalogue
Feature: Catalogue des placements
  Le catalogue expose les trois supports d'epargne Horizon (Prudent, Equilibre, Dynamique)
  avec leur profil de risque et leur hypothese de progression annuelle.
  Il permet egalement de consulter la fiche detaillee d'un produit par son code.

  Background:
    Given le catalogue Horizon est initialise avec les produits de reference

  @chemin-nominal
  Scenario: Consultation de la liste des placements
    When l'utilisateur demande la page du catalogue
    Then le systeme affiche les 3 placements du catalogue
    And le systeme indique l'etape 1 du parcours
    And le titre de la page est "Catalogue des placements"

  @chemin-nominal
  Scenario Outline: Consultation de la fiche d'un placement existant
    When l'utilisateur demande la fiche du produit de code "<code>"
    Then le systeme affiche la fiche du produit nomme "<nom>"
    And le profil affiche est "<profil>"
    And le taux annuel affiche est "<taux>"

    Examples:
      | code      | nom                | profil    | taux |
      | PRUDENT   | Horizon Prudent    | Prudent   | 0.02 |
      | EQUILIBRE | Horizon Équilibre  | Équilibré | 0.04 |
      | DYNAMIQUE | Horizon Dynamique  | Dynamique | 0.06 |

  @chemin-nominal
  Scenario: Le taux annuel est egalement expose en pourcentage
    When l'utilisateur demande la fiche du produit de code "DYNAMIQUE"
    Then le taux affiche en pourcentage est "6.00"

  @gestion-erreur
  Scenario: Consultation de la fiche d'un placement inconnu
    When l'utilisateur demande la fiche du produit de code "INEXISTANT"
    Then le systeme repond avec le code HTTP 404

  @cas-limite
  Scenario Outline: Code produit vide ou mal formate sur la fiche produit
    When l'utilisateur demande la fiche du produit de code "<code>"
    Then le systeme repond avec le code HTTP 404

    Examples:
      | code       |
      |            |
      | prudent    |
      |  PRUDENT   |
      | PRUDENT123 |

  @cas-limite
  Scenario: Recherche d'un produit sans code transmis
    When l'utilisateur recherche un produit sans fournir de code
    Then aucun produit n'est retourne par le catalogue

  @gestion-erreur
  Scenario: Methode HTTP non autorisee sur le catalogue
    When l'utilisateur soumet le catalogue en methode POST
    Then le systeme repond avec le code HTTP 405

  @cas-limite
  Scenario: La liste des produits n'est pas modifiable
    When le systeme tente d'ajouter un produit a la liste retournee par le catalogue
    Then une erreur de modification interdite est levee
