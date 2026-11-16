@exploitation @telemetrie
Feature: Telemetrie du SPA
  Chaque erreur non interceptee produit exactement une charge utile portant la route et la version
  deployee, sans donnee personnelle ni jeton CSRF ; les Web Vitals des routes mesurees sont
  remontees au collecteur. Ces scenarios d'exploitation completent les 88 scenarios declares sans
  en faire partie.

  Scenario: Une erreur non interceptee est remontee une seule fois sans donnee personnelle
    Given un collecteur de telemetrie est joignable
    When une erreur non interceptee est injectee sur la route "/souscription/simulation"
    Then le collecteur recoit exactement une charge utile
    And la charge utile porte la route et la version deployee
    And la charge utile ne porte aucune donnee personnelle ni jeton CSRF

  Scenario: Les Web Vitals de la route d'accueil sont remontees
    Given un collecteur de telemetrie est joignable
    Then le collecteur recoit les metriques LCP, INP et CLS de la route mesuree
