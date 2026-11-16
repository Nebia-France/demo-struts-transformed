@domaine-fonctionnel @formulaire-souscription
Feature: Formulaire de souscription
  Le formulaire de souscription porte les valeurs saisies au fil du parcours. Il propose
  des valeurs par defaut pour la simulation et neutralise les valeurs nulles afin
  d'eviter toute erreur d'affichage dans les pages du parcours.

  @chemin-nominal
  Scenario: Valeurs par defaut du formulaire
    When le systeme cree un nouveau formulaire de souscription
    Then le versement initial par defaut est "1000,00"
    And la mensualite par defaut est "100,00"
    And la duree par defaut est "5"
    And les champs d'identification du souscripteur sont vides
    And l'acceptation des informations est a faux

  @cas-limite
  Scenario Outline: Les valeurs nulles sont remplacees par une chaine vide
    When l'utilisateur renseigne le champ "<champ>" avec une valeur nulle
    Then le champ "<champ>" du formulaire vaut une chaine vide

    Examples:
      | champ     |
      | firstName |
      | lastName  |
      | email     |
      | birthDate |
      | initial   |
      | monthly   |
      | years     |

  @chemin-nominal
  Scenario Outline: Les valeurs saisies sont conservees telles quelles
    When l'utilisateur renseigne le champ "<champ>" avec la valeur "<valeur>"
    Then le champ "<champ>" du formulaire vaut "<valeur>"

    Examples:
      | champ     | valeur                |
      | firstName | Camille               |
      | lastName  | Durand                |
      | email     | camille@example.fr    |
      | birthDate | 12/04/1985            |
      | initial   | 2500,00               |
      | monthly   | 250,00                |
      | years     | 12                    |

  @chemin-nominal
  Scenario: Acceptation des informations par l'utilisateur
    When l'utilisateur accepte les informations et les hypotheses de simulation
    Then l'acceptation des informations est a vrai
