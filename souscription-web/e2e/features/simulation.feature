@domaine-fonctionnel @simulation
Feature: Simulation du placement
  Le moteur de simulation capitalise mensuellement un versement initial et une mensualite
  sur une duree exprimee en annees, selon l'hypothese de progression annuelle du produit.
  Les montants saisis sont prealablement controles par le validateur d'entrees.

  Background:
    Given le produit retenu est "EQUILIBRE" avec un taux annuel de "0.04"

  @chemin-nominal
  Scenario: Simulation d'un placement standard
    When le systeme calcule la simulation avec un initial de "1000,00", une mensualite de "100,00" et une duree de 5 ans
    Then la simulation retourne un versement initial de "1000.00"
    And la simulation retourne une mensualite de "100.00"
    And la simulation retourne une duree de 5 ans
    And le total verse de la simulation est "7000.00"
    And le capital final de la simulation est superieur au total verse
    And les gains de la simulation sont egaux au capital final moins le total verse

  @chemin-nominal
  Scenario Outline: Validation reussie des montants de simulation
    When l'utilisateur renseigne un initial de "<initial>", une mensualite de "<mensualite>" et une duree de "<duree>"
    Then aucune erreur de validation de la simulation n'est signalee

    Examples:
      | initial   | mensualite | duree |
      | 100       | 0          | 1     |
      | 100,00    | 0,00       | 30    |
      | 100000    | 2000       | 15    |
      | 1000.50   | 99.99      | 9     |

  @cas-limite
  Scenario Outline: Bornes du versement initial
    When l'utilisateur renseigne un initial de "<initial>", une mensualite de "100,00" et une duree de "5"
    Then le champ "initial" est en erreur : <en_erreur>

    Examples:
      | initial   | en_erreur |
      | 99,99     | oui       |
      | 100,00    | non       |
      | 100000,00 | non       |
      | 100000,01 | oui       |

  @cas-limite
  Scenario Outline: Bornes de la mensualite
    When l'utilisateur renseigne un initial de "1000,00", une mensualite de "<mensualite>" et une duree de "5"
    Then le champ "monthly" est en erreur : <en_erreur>

    Examples:
      | mensualite | en_erreur |
      | 0,00       | non       |
      | 2000,00    | non       |
      | 2000,01    | oui       |

  @cas-limite
  Scenario Outline: Bornes de la duree en annees
    When l'utilisateur renseigne un initial de "1000,00", une mensualite de "100,00" et une duree de "<duree>"
    Then le champ "years" est en erreur : <en_erreur>

    Examples:
      | duree | en_erreur |
      | 0     | oui       |
      | 1     | non       |
      | 30    | non       |
      | 31    | oui       |
      | 5,5   | oui       |
      |       | oui       |

  @gestion-erreur
  Scenario Outline: Format de montant refuse
    When le systeme normalise le montant "<saisie>"
    Then le montant normalise est absent

    Examples:
      | saisie     |
      |            |
      | 1 000,00   |
      | 1000,000   |
      | 1234567    |
      | abc        |
      | -100       |

  @chemin-nominal
  Scenario Outline: Normalisation des montants saisis
    When le systeme normalise le montant "<saisie>"
    Then le montant normalise vaut "<valeur>"

    Examples:
      | saisie   | valeur  |
      | 100      | 100     |
      | 1000,50  | 1000.50 |
      | 1000.50  | 1000.50 |
      | 999999   | 999999  |

  @gestion-erreur
  Scenario Outline: Parametres de simulation rejetes par le moteur de calcul
    When le systeme calcule la simulation avec les parametres bruts initial "<initial>", mensualite "<mensualite>", duree <duree> et taux "<taux>"
    Then une erreur "Paramètres de simulation invalides" est levee

    Examples:
      | initial  | mensualite | duree | taux |
      |          | 100.00     | 5     | 0.04 |
      | 1000.00  |            | 5     | 0.04 |
      | 1000.00  | 100.00     | 5     |      |
      | 99.99    | 100.00     | 5     | 0.04 |
      | 100000.01| 100.00     | 5     | 0.04 |
      | 1000.000 | 100.00     | 5     | 0.04 |
      | 1000.00  | -1.00      | 5     | 0.04 |
      | 1000.00  | 2000.01    | 5     | 0.04 |
      | 1000.00  | 100.00     | 0     | 0.04 |
      | 1000.00  | 100.00     | 31    | 0.04 |
      | 1000.00  | 100.00     | 5     | -0.01|
      | 1000.00  | 100.00     | 5     | 1.01 |

  @cas-limite
  Scenario: Simulation avec une hypothese de progression nulle
    Given le produit retenu est "PRUDENT" avec un taux annuel de "0"
    When le systeme calcule la simulation avec un initial de "1000,00", une mensualite de "100,00" et une duree de 1 ans
    Then le capital final de la simulation est egal au total verse
    And les gains de la simulation sont nuls

  @cas-limite
  Scenario: Simulation sans mensualite
    When le systeme calcule la simulation avec un initial de "100,00", une mensualite de "0,00" et une duree de 1 ans
    Then le total verse de la simulation est "100.00"
    And le capital final de la simulation est superieur au total verse
