@domaine-fonctionnel @confirmation-souscription
Feature: Confirmation et enregistrement de la souscription
  La confirmation recalcule la simulation a partir du taux du produit, enregistre la
  souscription de maniere idempotente grace a la cle de confirmation, puis affiche
  la reference attribuee au souscripteur.

  Background:
    Given l'utilisateur a enregistre une simulation pour le produit "EQUILIBRE"
    And l'utilisateur a accepte les informations et les hypotheses de simulation

  @chemin-nominal
  Scenario: Confirmation d'une demande complete
    When l'utilisateur confirme la demande avec la cle de confirmation du brouillon
    Then la souscription est enregistree avec une reference unique
    And le systeme redirige l'utilisateur vers "/confirmation.do"
    And le brouillon est retire de la session

  @chemin-nominal
  Scenario: Affichage de la page de confirmation
    Given la souscription de l'utilisateur a ete enregistree
    When l'utilisateur consulte la page de confirmation
    Then le systeme indique l'etape 5 du parcours
    And le titre de la page est "Confirmation de la souscription"
    And la reference de la souscription est affichee
    And la date de creation de la souscription est affichee au format "JJ/MM/AAAA à HH:mm"

  @chemin-nominal
  Scenario: Le capital final est recalcule au moment de la confirmation
    When l'utilisateur confirme la demande avec la cle de confirmation du brouillon
    Then la simulation enregistree est recalculee avec le taux annuel du produit
    And le capital final enregistre correspond au capital recalcule

  @cas-limite
  Scenario: Double soumission de la confirmation avec la meme cle
    When l'utilisateur confirme la demande avec la cle de confirmation du brouillon
    And l'utilisateur soumet a nouveau la confirmation avec la meme cle
    Then le systeme redirige l'utilisateur vers "/confirmation.do"
    And une seule souscription est enregistree

  @cas-limite
  Scenario: L'enregistrement est idempotent pour une cle de confirmation deja utilisee
    Given une souscription existe deja pour la cle de confirmation du brouillon
    When le systeme enregistre la souscription pour cette cle
    Then la souscription existante est retournee sans nouvel enregistrement

  @gestion-erreur
  Scenario: Cle de confirmation invalide
    When l'utilisateur confirme la demande avec une cle de confirmation erronee
    Then le systeme repond avec le code HTTP 409
    And aucune souscription n'est enregistree

  @gestion-erreur
  Scenario: Conditions non acceptees
    Given l'utilisateur n'a pas accepte les informations et les hypotheses de simulation
    When l'utilisateur confirme la demande avec la cle de confirmation du brouillon
    Then le systeme reaffiche le recapitulatif avec l'erreur sur le champ "accepted"
    And aucune souscription n'est enregistree

  @gestion-erreur
  Scenario: Indisponibilite de l'enregistrement
    Given l'enregistrement des souscriptions est momentanement indisponible
    When l'utilisateur confirme la demande avec la cle de confirmation du brouillon
    Then le systeme repond avec le code HTTP 503
    And le systeme reaffiche le recapitulatif avec l'erreur sur le champ "storage"
    And la saisie de l'utilisateur est conservee

  @gestion-erreur
  Scenario: Confirmation d'un parcours incomplet
    Given le brouillon ne contient pas de souscripteur
    When le systeme tente de confirmer le brouillon
    Then une erreur "Parcours incomplet" est levee

  @gestion-erreur
  Scenario: Confirmation d'un parcours sans simulation
    Given le brouillon ne contient pas de simulation
    When le systeme tente de confirmer le brouillon
    Then une erreur "Parcours incomplet" est levee

  @cas-limite
  Scenario: Recherche d'une souscription par une cle inconnue
    When le systeme recherche une souscription avec la cle "cle-inconnue"
    Then aucune souscription n'est retournee

  @chemin-nominal
  Scenario: Recherche d'une souscription enregistree par sa cle
    Given la souscription de l'utilisateur a ete enregistree
    When le systeme recherche la souscription avec la cle de confirmation utilisee
    Then la souscription retournee porte les donnees du souscripteur et de la simulation

  @cas-limite
  Scenario: Consultation de la confirmation sans souscription aboutie
    Given aucune souscription n'a ete enregistree pour la session
    When l'utilisateur consulte la page de confirmation
    Then le systeme redirige l'utilisateur vers "/catalogue.do?notice=parcours"
