@domaine-fonctionnel @initialisation-application
Feature: Initialisation et arret de l'application
  Au demarrage, l'application enregistre le pilote H2, cree le repertoire de donnees avec
  des permissions restreintes, initialise le schema de la base puis publie les services
  metier dans le contexte de servlet. A l'arret, elle libere les pilotes JDBC charges.

  @chemin-nominal
  Scenario: Demarrage nominal de l'application
    Given le repertoire de donnees "data/souscriptions" est accessible en ecriture
    When le conteneur initialise le contexte de l'application
    Then le schema de la base des souscriptions est cree
    And les services catalogue, validateur, simulation et souscription sont disponibles
    And l'application est publiee dans le contexte de servlet

  @chemin-nominal
  Scenario: Les permissions du repertoire de donnees sont restreintes
    Given le systeme de fichiers supporte les permissions POSIX
    When le conteneur initialise le contexte de l'application
    Then le repertoire de donnees porte les permissions "rwx------"
    And le fichier de base de donnees porte les permissions "rw-------"

  @cas-limite
  Scenario: Le chemin de la base est lu depuis la propriete systeme
    Given la propriete systeme "horizon.data" vaut "/tmp/horizon/souscriptions"
    When le conteneur initialise le contexte de l'application
    Then la base est ouverte sur le chemin "/tmp/horizon/souscriptions"

  @cas-limite
  Scenario: Le systeme de fichiers ne supporte pas les permissions POSIX
    Given le systeme de fichiers ne supporte pas les permissions POSIX
    When le conteneur initialise le contexte de l'application
    Then l'initialisation aboutit sans application de permissions POSIX

  @gestion-erreur
  Scenario Outline: Echec d'initialisation de l'application
    Given l'initialisation echoue a cause de "<cause>"
    When le conteneur initialise le contexte de l'application
    Then une erreur "Initialisation de l'application impossible" est levee
    And un message d'erreur d'initialisation est journalise

    Examples:
      | cause                                |
      | repertoire de donnees inaccessible   |
      | schema de base introuvable           |
      | pilote H2 absent du classpath        |

  @gestion-erreur
  Scenario: Acces aux services avant initialisation
    Given le contexte de servlet ne contient pas l'application
    When un composant demande les services de l'application
    Then une erreur "Application non initialisée" est levee

  @chemin-nominal
  Scenario: Arret de l'application
    Given l'application est initialisee
    When le conteneur detruit le contexte de l'application
    Then les pilotes JDBC charges par l'application sont liberes

  @gestion-erreur
  Scenario: Echec de liberation d'un pilote JDBC a l'arret
    Given la liberation d'un pilote JDBC echoue
    When le conteneur detruit le contexte de l'application
    Then un message indiquant l'echec de liberation du pilote est journalise
    And l'arret de l'application se poursuit
