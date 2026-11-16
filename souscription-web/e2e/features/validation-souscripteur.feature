@domaine-fonctionnel @validation-souscripteur
Feature: Validation de l'identite du souscripteur
  Le validateur d'entrees controle le prenom, le nom, l'adresse electronique et la date
  de naissance saisis par le souscripteur avant de construire le souscripteur du dossier.
  La souscription est reservee aux personnes majeures.

  Background:
    Given la date du jour du systeme est le "15/06/2024"
    And le formulaire de souscription est vide

  @chemin-nominal
  Scenario: Saisie complete et valide du souscripteur
    When l'utilisateur renseigne le prenom "Camille" et le nom "Durand"
    And l'utilisateur renseigne l'adresse electronique "camille.durand@example.fr"
    And l'utilisateur renseigne la date de naissance "12/04/1985"
    Then aucune erreur de validation du souscripteur n'est signalee
    And le souscripteur construit porte le prenom "Camille" et le nom "Durand"
    And la date de naissance du souscripteur est affichee "12/04/1985"

  @chemin-nominal
  Scenario: Les espaces superflus sont supprimes des donnees du souscripteur
    When l'utilisateur renseigne le prenom "  Camille  " et le nom "  Durand  "
    And l'utilisateur renseigne l'adresse electronique "  camille@example.fr  "
    And l'utilisateur renseigne la date de naissance "12/04/1985"
    Then aucune erreur de validation du souscripteur n'est signalee
    And le souscripteur construit porte le prenom "Camille" et le nom "Durand"
    And l'adresse electronique du souscripteur est "camille@example.fr"

  @gestion-erreur
  Scenario Outline: Prenom ou nom invalide
    When l'utilisateur renseigne le prenom "<prenom>" et le nom "<nom>"
    And l'utilisateur renseigne l'adresse electronique "camille@example.fr"
    And l'utilisateur renseigne la date de naissance "12/04/1985"
    Then le champ "<champ>" est signale en erreur

    Examples:
      | prenom                | nom     | champ     |
      |                       | Durand  | firstName |
      | Camille               |         | lastName  |
      | PRENOM_DE_81_CARACTERES | Durand | firstName |
      | Camille               | NOM_DE_81_CARACTERES | lastName |

  @cas-limite
  Scenario Outline: Longueurs limites acceptees pour le prenom
    When l'utilisateur renseigne un prenom de <longueur> caracteres et le nom "Durand"
    And l'utilisateur renseigne l'adresse electronique "camille@example.fr"
    And l'utilisateur renseigne la date de naissance "12/04/1985"
    Then le champ "firstName" est en erreur : <en_erreur>

    Examples:
      | longueur | en_erreur |
      | 1        | non       |
      | 80       | non       |
      | 81       | oui       |

  @gestion-erreur
  Scenario: Un caractere de controle dans le nom est refuse
    When l'utilisateur renseigne un nom contenant un caractere de controle
    Then le champ "lastName" est signale en erreur

  @gestion-erreur
  Scenario Outline: Adresse electronique invalide
    When l'utilisateur renseigne le prenom "Camille" et le nom "Durand"
    And l'utilisateur renseigne l'adresse electronique "<email>"
    And l'utilisateur renseigne la date de naissance "12/04/1985"
    Then le champ "email" est signale en erreur

    Examples:
      | email                   |
      |                         |
      | camille.durand          |
      | camille@example         |
      | camille example@fr.com  |
      | <camille>@example.fr    |

  @cas-limite
  Scenario: Une adresse electronique de plus de 254 caracteres est refusee
    When l'utilisateur renseigne une adresse electronique de 255 caracteres
    Then le champ "email" est signale en erreur

  @gestion-erreur
  Scenario Outline: Date de naissance invalide ou non conforme
    When l'utilisateur renseigne le prenom "Camille" et le nom "Durand"
    And l'utilisateur renseigne l'adresse electronique "camille@example.fr"
    And l'utilisateur renseigne la date de naissance "<date>"
    Then le champ "birthDate" est signale en erreur avec le message contenant "<message>"

    Examples:
      | date       | message           |
      |            | format JJ/MM/AAAA |
      | 1985-04-12 | format JJ/MM/AAAA |
      | 31/02/1985 | format JJ/MM/AAAA |
      | 12/13/1985 | format JJ/MM/AAAA |
      | 12/04/2030 | futur             |
      | 12/04/2010 | majeures          |

  @cas-limite
  Scenario Outline: Age limite de la majorite
    When l'utilisateur renseigne le prenom "Camille" et le nom "Durand"
    And l'utilisateur renseigne l'adresse electronique "camille@example.fr"
    And l'utilisateur renseigne la date de naissance "<date>"
    Then le champ "birthDate" est en erreur : <en_erreur>

    Examples:
      | date       | en_erreur |
      | 15/06/2006 | non       |
      | 16/06/2006 | oui       |
      | 15/06/2024 | oui       |

  @gestion-erreur
  Scenario: La construction du souscripteur est refusee en cas d'erreur de saisie
    When l'utilisateur renseigne le prenom "" et le nom ""
    And le systeme tente de construire le souscripteur
    Then une erreur "Souscripteur invalide" est levee
