@exploitation @resilience
Feature: Resilience du SPA face a la passerelle et a la perte d'une replique
  Contrat de resilience (contracts/resilience-contract.json) : timeout client de 10 secondes,
  aucune tentative de rejeu, 6 appels concurrents au maximum vers la passerelle. Tout l'etat du
  parcours est cote serveur : la perte d'une replique du SPA n'entraine aucune perte de parcours.
  Ces scenarios d'exploitation completent les 88 scenarios declares sans en faire partie.

  Scenario: Une reponse trop lente de la passerelle est interrompue sans rejeu
    Given la passerelle retarde la reponse de 12 secondes
    When l'utilisateur soumet une requete vers "/api/v1/products"
    Then la requete se termine par une erreur de delai d'attente au bout de 10 secondes
    And les journaux d'acces de la passerelle ne montrent qu'une seule requete
    And au plus 6 requetes sont en vol simultanement vers la passerelle

  Scenario: L'arret d'une replique du SPA en cours de parcours ne perd pas le parcours
    Given le SPA est deploye avec 3 repliques sans affinite de session
    When une replique est arretee pendant le parcours de l'utilisateur
    Then le parcours reste intact car tout l'etat est cote serveur
