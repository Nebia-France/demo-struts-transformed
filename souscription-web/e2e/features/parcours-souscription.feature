@domaine-fonctionnel @parcours-souscription
Feature: Parcours de souscription en cinq etapes
  Le parcours guide l'utilisateur du choix du produit (etape 1) a la confirmation (etape 5),
  en passant par l'identification du souscripteur (etape 2), la simulation (etape 3)
  et le recapitulatif (etape 4). Le brouillon est conserve en session.

  Background:
    Given l'application Horizon est demarree
    And l'utilisateur dispose d'une session valide

  @chemin-nominal
  Scenario: Demarrage du parcours depuis un produit du catalogue
    When l'utilisateur demarre le parcours pour le produit "EQUILIBRE"
    Then un brouillon est cree en session pour le produit "EQUILIBRE"
    And le systeme redirige l'utilisateur vers "/souscripteur.do"

  @chemin-nominal
  Scenario: Parcours complet jusqu'au recapitulatif
    Given l'utilisateur a demarre le parcours pour le produit "DYNAMIQUE"
    When l'utilisateur soumet une identification valide du souscripteur
    Then le systeme redirige l'utilisateur vers "/simulation.do"
    When l'utilisateur soumet une simulation valide avec la commande "continuer"
    Then le systeme redirige l'utilisateur vers "/recapitulatif.do"
    When l'utilisateur consulte le recapitulatif
    Then le systeme indique l'etape 4 du parcours
    And le titre de la page est "Récapitulatif de la demande"

  @chemin-nominal
  Scenario: Recalcul de la simulation sans quitter l'etape
    Given l'utilisateur a renseigne un souscripteur valide pour le produit "PRUDENT"
    When l'utilisateur soumet une simulation valide avec la commande "calculer"
    Then le systeme redirige l'utilisateur vers "/simulation.do"
    And le brouillon contient une simulation calculee

  @chemin-nominal
  Scenario: Les donnees deja saisies sont reaffichees a l'etape souscripteur
    Given l'utilisateur a renseigne un souscripteur valide pour le produit "PRUDENT"
    When l'utilisateur revient sur l'etape d'identification du souscripteur
    Then le formulaire est prerempli avec les donnees du souscripteur enregistre
    And le systeme indique l'etape 2 du parcours

  @chemin-nominal
  Scenario: Les donnees de simulation deja saisies sont reaffichees
    Given l'utilisateur a enregistre une simulation pour le produit "EQUILIBRE"
    When l'utilisateur revient sur l'etape de simulation
    Then le formulaire est prerempli avec les montants de la simulation enregistree
    And le systeme indique l'etape 3 du parcours

  @gestion-erreur
  Scenario: Saisie invalide du souscripteur a l'etape 2
    Given l'utilisateur a demarre le parcours pour le produit "EQUILIBRE"
    When l'utilisateur soumet une identification invalide du souscripteur
    Then le systeme reaffiche l'etape d'identification avec les erreurs de validation
    And aucun souscripteur n'est enregistre dans le brouillon

  @gestion-erreur
  Scenario: Saisie invalide de la simulation a l'etape 3
    Given l'utilisateur a renseigne un souscripteur valide pour le produit "EQUILIBRE"
    When l'utilisateur soumet une simulation invalide avec la commande "calculer"
    Then le systeme reaffiche l'etape de simulation avec les erreurs de validation
    And aucune simulation n'est enregistree dans le brouillon

  @gestion-erreur
  Scenario Outline: Commande inconnue a l'etape de simulation
    Given l'utilisateur a renseigne un souscripteur valide pour le produit "EQUILIBRE"
    When l'utilisateur soumet la simulation avec la commande "<commande>"
    Then le systeme repond avec le code HTTP 400

    Examples:
      | commande  |
      |           |
      | supprimer |
      | CALCULER  |

  @gestion-erreur
  Scenario: Demarrage du parcours avec un produit inconnu
    When l'utilisateur demarre le parcours pour le produit "INEXISTANT"
    Then le systeme repond avec le code HTTP 400

  @gestion-erreur
  Scenario Outline: Methode HTTP non conforme a l'etape du parcours
    Given l'utilisateur a enregistre une simulation pour le produit "EQUILIBRE"
    When l'utilisateur appelle "<chemin>" en methode "<methode>"
    Then le systeme repond avec le code HTTP 405

    Examples:
      | chemin             | methode |
      | /commencer.do      | GET     |
      | /recapitulatif.do  | POST    |
      | /confirmation.do   | POST    |
      | /confirmer.do      | GET     |

  @gestion-erreur
  Scenario: Chemin inconnu dans le parcours
    Given l'utilisateur a enregistre une simulation pour le produit "EQUILIBRE"
    When l'utilisateur appelle un chemin inconnu du parcours
    Then le systeme repond avec le code HTTP 404

  @cas-limite
  Scenario: Reprise du parcours sans brouillon en session
    Given aucun brouillon n'existe en session
    When l'utilisateur consulte l'etape de simulation
    Then le systeme redirige l'utilisateur vers "/catalogue.do?notice=parcours"

  @cas-limite
  Scenario: Acces a la simulation avant l'identification du souscripteur
    Given l'utilisateur a demarre le parcours pour le produit "EQUILIBRE"
    When l'utilisateur consulte l'etape de simulation
    Then le systeme redirige l'utilisateur vers "/souscripteur.do"

  @cas-limite
  Scenario: Acces au recapitulatif avant la simulation
    Given l'utilisateur a renseigne un souscripteur valide pour le produit "EQUILIBRE"
    When l'utilisateur consulte le recapitulatif
    Then le systeme redirige l'utilisateur vers "/simulation.do"

  @cas-limite
  Scenario: Modification du souscripteur apres simulation invalide la cle de confirmation
    Given l'utilisateur a enregistre une simulation pour le produit "EQUILIBRE"
    When l'utilisateur modifie l'identification du souscripteur avec des donnees valides
    Then la cle de confirmation du brouillon est renouvelee
