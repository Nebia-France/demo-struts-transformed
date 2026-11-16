# Horizon — environnement local complet

```sh
./scripts/start-all.sh
```

Prerequis : **Docker Desktop** demarre (ou Docker Engine avec Compose v2+),
Bash, curl et OpenSSL. Java 21, Maven et Node sont fournis par les images de
construction. Le premier lancement telecharge les images et dependances ; il peut
prendre plusieurs minutes.

Sur un moteur Docker local Unix (dont Docker Desktop macOS), le lancement utilise
une configuration CLI isolee dans `.horizon/docker-public` pour les images publiques,
afin d'eviter les blocages du gestionnaire d'identifiants. Le contexte et les
identifiants habituels restent inchanges. Pour utiliser explicitement votre
configuration CLI existante : `HORIZON_USE_DOCKER_CONFIG=1 ./scripts/start-all.sh`.

Le lockfile npm de cet export pointe vers un Artifactory prive. Le Dockerfile local
du web remplace ces URL par celles de npm public uniquement dans sa copie de
construction ; versions, empreintes d'integrite et lockfile source sont conserves.

Le script construit et lance les **cinq applications reelles** : web Angular,
gateway, catalogue, simulation et souscription. Il ajoute PostgreSQL 16 avec un
volume persistant et une entree Nginx commune a la SPA et a `/api/`.
Il attend les sondes de disponibilite puis execute le parcours HTTP existant :
catalogue → cookie CSRF → brouillon → souscripteur → simulation → recapitulatif →
confirmation → lecture de la souscription enregistree.
Chaque controle cree une souscription de demonstration pour Alice Durand
(`alice@example.com`) dans la base locale.

Ouvrir **https://localhost:8443**. Les certificats sont signes par une autorite
locale generee dans `.horizon/pki/ca.crt` : accepter l'avertissement du navigateur
ou ajouter cette autorite aux certificats de confiance de la machine. Le script
n'installe rien dans le trousseau et ses controles curl verifient cette autorite.
HTTPS permet au navigateur d'utiliser le cookie CSRF `Secure` existant.

```sh
./scripts/start-all.sh status
./scripts/start-all.sh logs
./scripts/start-all.sh logs souscription-service
./scripts/start-all.sh check
./scripts/start-all.sh down
./scripts/start-all.sh up --no-build  # apres une premiere construction reussie
HORIZON_HTTPS_PORT=9443 ./scripts/start-all.sh  # port memorise pour les commandes suivantes
```

`down` conserve le volume PostgreSQL et `.horizon/`. Les secrets generes sont
reutilises entre les lancements et exclus du contexte de construction Docker.
Ne pas supprimer `.horizon/local.env` en conservant le volume : PostgreSQL garde
le mot de passe de son initialisation. Les certificats de workload durent 30 jours ;
pour les renouveler, arreter la pile, supprimer uniquement `.horizon/pki`, puis
relancer le script. Un demarrage recree les conteneurs pour relire la configuration.

Seul le port HTTPS est publie, sur `127.0.0.1`. Les appels vers le catalogue et
la simulation utilisent mTLS avec les identites SPIFFE attendues. Le gateway
appelle le service de souscription en HTTP sur le reseau Docker prive ; PostgreSQL
y utilise egalement une connexion sans TLS. L'export OpenTelemetry est desactive
pour ce lancement local, sans collecteur. Cette configuration est destinee au
developpement local ; les fichiers de deploiement de production sont conserves.

`global/platform/pom.xml` fournit le parent Maven reference par le service de
simulation mais absent de l'export initial, avec la version Quarkus 3.20.6.2
documentee par ce module. Les autres services conservent leurs propres versions.
La construction ne lance pas les suites unitaires/CI ; le controle de fin est un
parcours HTTP d'integration sur les applications construites.

Les projections `ProductDetail` du gateway et du service de souscription ignorent
explicitement le champ optionnel `ratePercent` ajoute par le catalogue. Les autres
champs inconnus restent rejetes : cet ajustement permet aux versions de cet export
de communiquer sans desactiver globalement la validation JSON.
Le client du gateway relaie aussi la paire CSRF vers la defense secondaire du
service de souscription, en traduisant le nom de cookie `XSRF-TOKEN` vers son nom
interne `csrf-token`. Les autres cookies du navigateur ne sont pas relayes.
