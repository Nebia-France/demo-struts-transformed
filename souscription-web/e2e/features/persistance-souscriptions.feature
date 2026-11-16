@domaine-fonctionnel @persistance-souscriptions
Feature: Persistance des souscriptions en base H2
  Le referentiel de souscriptions cree le schema a partir du script SQL embarque et
  enregistre chaque souscription dans une transaction, en garantissant l'unicite de la
  cle de confirmation et en restituant la souscription existante en cas de doublon.

  Background:
    Given une base de souscriptions vide est disponible

  @chemin-nominal
  Scenario: Creation du schema de la base
    When le systeme initialise le referentiel de souscriptions
    Then la table des souscriptions existe

  @chemin-nominal
  Scenario: Enregistrement d'une souscription
    Given le referentiel de souscriptions est initialise
    When le systeme enregistre une souscription avec la cle "cle-001"
    Then la souscription enregistree possede une reference non vide
    And la souscription est relisible par la cle "cle-001"
    And les montants enregistres sont conformes a la simulation

  @cas-limite
  Scenario: Enregistrement repete avec la meme cle de confirmation
    Given le referentiel de souscriptions est initialise
    And une souscription existe pour la cle "cle-002"
    When le systeme enregistre une souscription avec la cle "cle-002"
    Then la reference retournee est identique a celle de la souscription existante
    And une seule ligne existe pour la cle "cle-002"

  @cas-limite
  Scenario: Conflit d'unicite concurrent sur la cle de confirmation
    Given le referentiel de souscriptions est initialise
    And l'insertion echoue avec le code SQL "23505" pour la cle "cle-003"
    And une souscription existe pour la cle "cle-003"
    When le systeme enregistre une souscription avec la cle "cle-003"
    Then la souscription existante est retournee sans nouvel enregistrement

  @gestion-erreur
  Scenario: Echec technique a l'insertion
    Given le referentiel de souscriptions est initialise
    And l'insertion echoue avec le code SQL "08006" pour la cle "cle-004"
    When le systeme enregistre une souscription avec la cle "cle-004"
    Then une erreur de base de donnees est propagee
    And la transaction est annulee

  @gestion-erreur
  Scenario: Script de schema absent
    Given le script "/db/schema.sql" est absent du classpath
    When le systeme initialise le referentiel de souscriptions
    Then une erreur "Schéma H2 absent" est levee

  @gestion-erreur
  Scenario: Base de donnees injoignable
    Given l'url de connexion de la base est invalide
    When le systeme initialise le referentiel de souscriptions
    Then une erreur de base de donnees est propagee

  @cas-limite
  Scenario: Relecture d'une cle inexistante
    Given le referentiel de souscriptions est initialise
    When le systeme recherche une souscription avec la cle "cle-absente"
    Then aucune souscription n'est retournee

  @chemin-nominal
  Scenario: Les donnees du produit et du souscripteur sont restituees a la relecture
    Given le referentiel de souscriptions est initialise
    And une souscription existe pour la cle "cle-005"
    When le systeme recherche une souscription avec la cle "cle-005"
    Then le produit restitue porte le code, le nom, le profil et le taux annuel enregistres
    And le souscripteur restitue porte le prenom, le nom, l'adresse electronique et la date de naissance enregistres
