@domaine-fonctionnel @securite-web
Feature: Filtre de securite des requetes HTTP
  Le filtre de securite impose l'encodage UTF-8, ajoute les entetes de protection du
  navigateur, restreint les methodes HTTP autorisees et verifie le jeton anti-CSRF
  sur les soumissions des actions du parcours.

  Background:
    Given l'application Horizon est demarree

  @chemin-nominal
  Scenario: Entetes de securite ajoutes a chaque reponse
    When l'utilisateur appelle "/catalogue.do" en methode "GET"
    Then la reponse porte l'entete "X-Content-Type-Options" avec la valeur "nosniff"
    And la reponse porte l'entete "X-Frame-Options" avec la valeur "DENY"
    And la reponse porte l'entete "Referrer-Policy" avec la valeur "no-referrer"
    And la reponse porte l'entete "Cache-Control" avec la valeur "no-store"
    And la reponse porte une politique de securite du contenu restrictive

  @chemin-nominal
  Scenario: L'encodage des requetes et des reponses est UTF-8
    When l'utilisateur appelle "/catalogue.do" en methode "GET"
    Then l'encodage de la requete est "UTF-8"
    And l'encodage de la reponse est "UTF-8"

  @chemin-nominal
  Scenario: Un jeton anti-CSRF est cree pour la session a l'acces d'une action
    Given la session de l'utilisateur ne contient aucun jeton anti-CSRF
    When l'utilisateur appelle "/catalogue.do" en methode "GET"
    Then un jeton anti-CSRF est present en session

  @chemin-nominal
  Scenario: Le jeton anti-CSRF existant est conserve
    Given la session de l'utilisateur contient le jeton anti-CSRF "jeton-existant"
    When l'utilisateur appelle "/catalogue.do" en methode "GET"
    Then le jeton anti-CSRF en session reste "jeton-existant"

  @chemin-nominal
  Scenario: Soumission acceptee avec le jeton anti-CSRF attendu
    Given la session de l'utilisateur contient le jeton anti-CSRF "jeton-valide"
    When l'utilisateur soumet "/souscripteur.do" avec le jeton anti-CSRF "jeton-valide"
    Then la requete est transmise a l'action du parcours

  @gestion-erreur
  Scenario Outline: Soumission refusee en cas de jeton anti-CSRF incorrect
    Given la session de l'utilisateur contient le jeton anti-CSRF "jeton-valide"
    When l'utilisateur soumet "/souscripteur.do" avec le jeton anti-CSRF "<jeton>"
    Then le systeme repond avec le code HTTP 403
    And la requete n'est pas transmise a l'action du parcours

    Examples:
      | jeton         |
      |               |
      | jeton-invalide|
      | JETON-VALIDE  |

  @gestion-erreur
  Scenario: Soumission refusee en l'absence de jeton anti-CSRF
    Given la session de l'utilisateur contient le jeton anti-CSRF "jeton-valide"
    When l'utilisateur soumet "/souscripteur.do" sans jeton anti-CSRF
    Then le systeme repond avec le code HTTP 403

  @cas-limite
  Scenario: Jeton anti-CSRF de plus de 100 caracteres refuse
    Given la session de l'utilisateur contient le jeton anti-CSRF "jeton-valide"
    When l'utilisateur soumet "/souscripteur.do" avec un jeton anti-CSRF de 101 caracteres
    Then le systeme repond avec le code HTTP 403

  @gestion-erreur
  Scenario Outline: Methodes HTTP non autorisees
    When l'utilisateur appelle "/catalogue.do" en methode "<methode>"
    Then le systeme repond avec le code HTTP 405
    And la reponse porte l'entete "Allow" avec la valeur "GET, HEAD, POST"

    Examples:
      | methode |
      | PUT     |
      | DELETE  |
      | PATCH   |
      | OPTIONS |
      | TRACE   |

  @chemin-nominal
  Scenario Outline: Methodes HTTP autorisees
    When l'utilisateur appelle "/catalogue.do" en methode "<methode>"
    Then le systeme ne repond pas avec le code HTTP 405

    Examples:
      | methode |
      | GET     |
      | HEAD    |

  @cas-limite
  Scenario: Les ressources statiques ne requierent pas de jeton anti-CSRF
    When l'utilisateur appelle "/assets/css/application.css" en methode "GET"
    Then aucune verification de jeton anti-CSRF n'est effectuee
    And la requete est transmise a la chaine de traitement
