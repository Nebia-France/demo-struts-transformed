# Boucle de retour développeur — souscription-gateway (W0-T08)

`quarkus:dev` remplace la boucle Cargo du projet source. Aucun conteneur de servlets externe
n'est déployé : le processus Maven héberge l'application et recompile à chaud.

## Commandes Cargo remplacées

| Commande source (Cargo) | Rôle | Remplacement Quarkus |
| --- | --- | --- |
| `mvn cargo:start` | démarre Tomcat et déploie le WAR | `mvn -f souscription-gateway/pom.xml quarkus:dev` |
| `mvn cargo:deploy` | redéploie le WAR après recompilation | live reload automatique (aucune commande) |
| `mvn cargo:stop` | arrête le conteneur | `Ctrl-C` / `q` dans la console dev |

## Vérification du live reload (exécutée)

1. `mvn -o -f souscription-gateway/pom.xml quarkus:dev`
   → `souscription-gateway 1.0.0 on JVM (powered by Quarkus 3.20.6.2) started in 1.210s.`
   `Listening on: http://localhost:18080. Management interface listening on http://localhost:9000.`
   `Profile dev activated. Live Coding activated.`
   Le port **18080** provient de `%dev.quarkus.http.port=18080` (`application.properties`).
2. Contrôles HTTP sur l'instance dev :
   - `GET http://localhost:18080/` → `302` (application servie, redirection Dev UI)
   - `GET http://localhost:9000/q/health` → `200` (health exposé sur le port management 9000)
3. Modification de `GatewayStartup.java` (message de log passé à
   `gateway ready [live-reload check], downstreams: %s`) puis nouvelle requête HTTP :
   ```
   INFO [io.qua.dep.dev.RuntimeUpdatesProcessor] Restarting quarkus due to changes in GatewayStartup.class.
   INFO [fr.dem.sou.gat.run.GatewayStartup] gateway ready [live-reload check], downstreams: catalogue, simulation, subscription
   ```
   **Résultat : live reload réussi** — rechargement déclenché par la requête, en ~0,5 s, sans
   redémarrage du processus Maven ni du JVM, sans aucune commande de redéploiement.
   Le message de log a ensuite été remis à sa valeur initiale.

## Durée de packaging à chaud (NFG-011)

| Commande | Durée mesurée | Cible | Verdict |
| --- | --- | --- | --- |
| `mvn -o -q -f souscription-gateway/pom.xml clean package` (cache Maven chaud) | **7,0 s** | ≤ 180 s | OK |
| `mvn -o -f souscription-gateway/pom.xml clean test` | 5,3 s (1 test, 0 échec) | — | OK |

## Conséquences build-time et réflexion (FG-018)

- Dans le source Struts 1.3, l'`ActionServlet` instanciait les `Action` par **réflexion** à partir
  de l'attribut `type` de chaque `<action>` de `struts-config.xml`
  (`fr.demo.souscription.action.CatalogAction`, `...SubscriptionAction`), et les `ActionForm`
  (`fr.demo.souscription.form.SubscriptionForm`) à partir des `<form-bean>`. La résolution était
  donc faite **au démarrage/à l'exécution**, par nom de classe.
- Avec Quarkus, ArC découvre et câble les beans **au moment du build** (indexation Jandex,
  génération de bytecode). Il n'existe plus de descripteur XML résolu par nom : une classe non
  référencée statiquement n'est pas enregistrée.
- Conséquence : toute recherche de classe résiduelle par réflexion (`Class.forName`,
  instanciation dynamique, désérialisation d'un type non atteignable statiquement) doit être
  déclarée via `@RegisterForReflection`, sous peine d'échec à l'exécution (y compris en JVM pour
  les beans non découverts).
- **État du module gateway : aucune recherche de classe par réflexion.** Preuve :
  `grep -rni "Class.forName\|newInstance\|getDeclaredMethod" souscription-gateway/src` → aucun
  résultat. Aucun `@RegisterForReflection` n'est donc nécessaire à ce stade.

## Non double-comptage — vues JSP/JSTL (TG-012)

Les bibliothèques de vue JSTL/JSP, `src/main/webapp/WEB-INF/jsp/` et
`src/main/webapp/assets/css/application.css` **n'ont aucun équivalent dans la gateway** : celle-ci
n'expose que des ressources REST JSON. L'effort correspondant est imputé à **`souscription-web`**
(SPA Angular) et ne doit pas être compté une seconde fois ici.

Preuve : `grep -rn "jsp\|jstl\|taglib" souscription-gateway/` → aucun résultat
(également vérifié sur `souscription-gateway/src` et `souscription-gateway/pom.xml`).

## Non-portage des clés de messages Struts (FG-014)

Les clés de `src/main/resources/ApplicationResources.properties` **ne sont pas portées dans la
gateway** :

- les libellés destinés à l'utilisateur relèvent de la SPA (`souscription-web`), qui porte l'i18n ;
- les `FieldErrors` de validation relèvent des services métier (`souscription-service`,
  `souscription-catalogue-service`, `souscription-simulation-service`), qui produisent les codes
  d'erreur transportés en JSON.

La gateway ne fait que relayer ces codes, sans table de messages.

Preuve : `grep -rn "ApplicationResources" souscription-gateway/` → aucun résultat.

## Expérience développeur distribuée (W6-T05, NFG-020)

La cible distribuée ne doit pas dégrader la boucle locale du monolithe (un seul processus + Tomcat
Cargo). Deux flux sont fournis ; voir aussi la section « souscription-gateway » du `README.md`.

### Flux (a) — passerelle seule, stubs de contrat

```sh
souscription-gateway/src/test/scripts/dev-stubs.sh      # ou : make -C souscription-gateway dev
```

Le script démarre trois doubles WireMock standalone sur `127.0.0.1:18750/18751/18752`, servant les
mappings **générés depuis `global/contracts/`** (`src/test/resources/wiremock/<service>/mappings`),
génère une clé de signature CSRF propre à la session, puis lance
`mvn -f souscription-gateway/pom.xml quarkus:dev` relié à ces doubles. Le JAR WireMock est la
dépendance de test déjà présente dans le dépôt Maven local (copiée une fois depuis Maven Central si
absente) : **aucun conteneur**. `src/test/scripts/journey-check.sh` exécute un parcours complet
(catalogue → fiche → jeton CSRF → brouillon → souscripteur → simulation → rattachement →
récapitulatif → confirmation → souscription confirmée).

Les stubs sont régénérés par `src/test/scripts/generate-stubs.sh` (ou `make stubs`) à partir des
trois `openapi.yaml` consommés et de `src/test/scripts/stub-fixtures.yaml` ; ne jamais éditer les
JSON à la main. `generate-stubs.sh --check` échoue si un stub n'est plus synchrone avec le contrat.

### Flux (b) — topologie locale complète, une commande

```sh
make -C souscription-gateway dev-topology          # ingress + SPA + gateway + 3 services (stubs)
make -C souscription-gateway dev-topology-check    # parcours complet via l'ingress :8088
make -C souscription-gateway dev-topology-down
```

`souscription-gateway/docker-compose.dev.yml` : ingress nginx même origine (`127.0.0.1:8088`,
`/api/` → gateway, `/` → `souscription-web`, `/q/*` → 404), image gateway construite par
`src/main/docker/Dockerfile.jvm` (plan 04), interface de management sur `127.0.0.1:9000`. Les trois
services sont, par défaut (`PROFILE=stubs`), des conteneurs `wiremock/wiremock:3.13.2` servant les
mêmes stubs de contrat sous les noms d'hôte des services ; `PROFILE=siblings` utilise les images des
services frères (`CATALOGUE_IMAGE`, `SIMULATION_IMAGE`, `SUBSCRIPTION_IMAGE`), dont la configuration
propre (base de données, profil local) relève de leurs dépôts.

### Mesures NFG-020 (exécutées)

| Mesure | Résultat | Preuve |
| --- | --- | --- |
| Temps de rechargement (édition → réponse) — *reload time* | **0,62 s** (`Live reload total time: 0.622s`, 0,66 s requête comprise) ; démarrage `quarkus:dev` 1,45 s | `dev-stubs.sh`, modification du message de `GatewayStartup.java` puis `GET /api/v1/products` → 200 (modification annulée ensuite) |
| Téléchargement de conteneur pour le flux (a) / la suite de tests — *container download* | **0** (0 download) | `dev-stubs.sh` et `mvn clean test` n'invoquent pas Docker ; WireMock tourne dans la JVM de test ou en JAR standalone |
| Commandes pour la topologie complète — *one command for the full topology* | **1** (one command : `make -C souscription-gateway dev-topology`) | 6 conteneurs `Healthy` ; `dev-topology-check` : parcours complet OK (201 brouillon, 201 confirmation, 200 lecture) via l'ingress ; SPA `GET /` 200 `text/html` ; `/q/health` 404 sur l'ingress |
| Services frères requis par la suite de la passerelle — *sibling services required* | **0** (0 sibling services) | `mvn -o -f souscription-gateway/pom.xml clean test` 270/270 et `verify -Pcucumber` (53 scénarios passerelle, 0 échec) sans aucun service frère : `DownstreamWireMockResource` démarre les doubles pour chaque `@QuarkusTest`/failsafe |

Sondes et contrats opérationnels du flux (a), sur le port de management 9000 :
`/q/health/live` 200, `/q/health/ready` 200, `/q/metrics` 200, `/q/openapi` 200.
