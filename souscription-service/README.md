# souscription-service

Service Quarkus 3.33.4.2 (LTS, depuis le plan 41 ; 3.20.1 auparavant) / Java 21 issu de la migration du monolithe Struts/Tomcat.
Le deploiement cible n'est plus un WAR deploye par `cargo-maven3-plugin` dans un
Tomcat telecharge, mais une image OCI durcie contenant le fast-jar Quarkus.

## Commandes

| But | Commande |
| --- | --- |
| Boucle de dev sans Docker (H2 en memoire) | `mvn -f souscription-service/pom.xml -Plocal-h2 quarkus:dev` |
| Boucle de dev (live reload + Dev Services) | `mvn -f souscription-service/pom.xml quarkus:dev` |
| Build et packaging fast-jar | `mvn -f souscription-service/pom.xml clean package` |
| Build + construction de l'image OCI | `mvn -f souscription-service/pom.xml clean package -Dquarkus.container-image.build=true` |

`quarkus:dev` expose l'application sur <http://localhost:8080> et demarre les
Dev Services (PostgreSQL conteneurise) : aucune URL de base n'est figee dans la
configuration.

Le profil Maven `local-h2` active les profils Quarkus `dev,local-h2` et ajoute
le pilote H2 uniquement pour ce lancement. Il n'exige ni Docker ni variables
`HORIZON_DB_*`. Les migrations Flyway existantes sont appliquees en mode de
compatibilite PostgreSQL, avec un alias de type `timestamptz` propre a H2.
Les donnees sont perdues a l'arret du processus. Le build standard et la
production restent sur PostgreSQL ; H2 ne remplace pas les tests de parite
PostgreSQL.

Les Dev Services exigent un moteur de conteneurs compatible Docker demarre et
accessible a Testcontainers. Sans ce moteur, utiliser une base PostgreSQL de
developpement dediee et definir `HORIZON_DB_URL`, `HORIZON_DB_USER` et
`HORIZON_DB_PASSWORD`, puis lancer avec
`-Dquarkus.datasource.devservices.enabled=false`. Pour une base strictement
locale sans TLS, ajouter
`-Dquarkus.datasource.jdbc.additional-jdbc-properties.sslmode=disable`.
Le profil dev applique les migrations Flyway au demarrage : ne pas le pointer
sur une base partagee ou de production.

En profil dev, le tracage reste actif sans export reseau et ne requiert pas de
collecteur OpenTelemetry. Pour exporter les traces, definir
`OTEL_TRACES_EXPORTER=otlp` et `OTEL_EXPORTER_OTLP_ENDPOINT` avec l'adresse du
collecteur (par defaut `http://localhost:4317` en dev uniquement). En production,
`OTEL_EXPORTER_OTLP_ENDPOINT` reste obligatoire.

Le packaging produit `target/quarkus-app/quarkus-run.jar`. Le `Dockerfile.jvm`
equivalent est fourni dans `src/main/docker/` pour un build sans Jib :

```
docker build -f souscription-service/src/main/docker/Dockerfile.jvm \
  -t horizon/souscription-service:dev souscription-service/
docker run --rm --read-only --tmpfs /tmp horizon/souscription-service:dev
```

## Durcissement runtime

L'image tourne en non-root (UID 185) avec systeme de fichiers racine en lecture
seule. Le contrat applique par la plateforme est decrit dans
`deploy/pod-security.yaml` : `runAsNonRoot: true`, `runAsUser: 185`,
`readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`,
`capabilities.drop: ["ALL"]`, et un unique volume `emptyDir` monte sur `/tmp`.

## Cibles mesurables de packaging

| Indicateur | Reference source | Cible | Mesure |
| --- | --- | --- | --- |
| Empreinte memoire (NFG-010) | 250-450 MB RSS (Tomcat/WAR) | **180-300 MB RSS** | `container_memory_working_set_bytes` sous charge |
| Duree de build | — | **< 5 min** | build Maven incluant image OCI et Dev Services |

## Porte de build

Depuis le plan 36 la porte de build du module est :

```bash
mvn -f souscription-service/pom.xml clean verify -Pcucumber
```

Elle remplace la porte `clean test` utilisee par les plans 01-35, qui reste valide et doit
continuer a passer. `verify` ajoute la phase failsafe : `maven-surefire-plugin` execute
`*Test.java` (tests unitaires et `@QuarkusTest`), `maven-failsafe-plugin` execute `*IT.java`
(bout en bout et suite Cucumber). Le harnais est entierement BOM-gere (`quarkus-junit5`,
`rest-assured`) et la base des tests d'integration vient de Dev Services PostgreSQL : ni Cargo,
ni Tomcat, ni conteneur applicatif externe n'intervient. `HarnessSmokeIT` en est la preuve ; il
est ignore, comme les tests de persistance, en l'absence de daemon de conteneurs.

### Parite du harnais (verification du plan 36)

La mesure de parite d'une migration d'infrastructure de test porte sur l'identite du harnais,
pas sur le comportement des tests migres. Elle est automatisee par `HarnessParityTest` :

| Critere | Attendu | Constat |
| --- | --- | --- |
| Framework | JUnit 5, aucune version inferieure a 5.10.2 | `junit-jupiter` 5.12.1 resolu par le BOM Quarkus ; les 5 fichiers de tests source sont deja en `org.junit.jupiter`, aucune conversion 4 -> 5 n'etait requise |
| Tests source | 14 methodes `@Test` conservees | 14 ; `pom.xml` et `src` de la source non modifies (relocalisation, pas suppression) |
| Tests du module | compte non decroissant, aucun test desactive sans condition | 377 tests unitaires (164 ignores faute de daemon de conteneurs) et 359 tests d'integration dont 167 scenarios Cucumber en porte complete ; aucune annotation de desactivation, les seules preconditions sont d'environnement (`@RequiresContainerRuntime`, `@EnabledUnlessPropertyFalse`) |
| Conteneur de servlets | 0 reference Cargo / Tomcat / port 18080 / profil `integration` | absent du POM du module, et aucun telechargement de Tomcat dans le build |
| jsoup | absent, ou >= 1.23.2 | absent des dependances (seule une note de contexte subsiste en commentaire) |
| Decoupage surefire / failsafe | surefire `*Test.java`, failsafe `*IT.java`, rapports failsafe peuples | conforme ; `target/failsafe-reports/` contient `HarnessSmokeIT` et le moteur Cucumber |
| Profil `%test` | suite autonome | `devservices.enabled=true`, `flyway.migrate-at-start=true`, clients REST distants seuls (aucun stub local), OTel coupe ; le pool reste inactif par defaut et est arme par `PostgresDevServicesProfile`, les clients REST sont bouchonnes par les tests (`CatalogueStubServer`, `SimulationStubServer`, demarres pour tout le profil par `PostgresWithContractStubsProfile`) |
| Suite Cucumber | executee depuis le plan 39 | `cucumber.execution.dry-run=false`, `cucumber.filter.tags=not @hors-perimetre-souscription-service` ; allocation des 88 scenarios dans `GHERKIN_ALLOCATION.md` |

#### Verifications differees pour cause d'environnement

Deux verifications sont differees pour cause d'environnement, sans ecart de parite :

- `HarnessSmokeIT` est collecte par failsafe mais ignore faute de daemon de conteneurs pour
  Dev Services (164 tests ignores pour le meme motif). Aucune base H2 n'est ajoutee pour le
  rendre vert.
- `mvn -DskipTests package` sur le build legacy echoue sur `maven-enforcer-plugin`
  (`RequireJavaVersion` : JDK 8 requis, absent de la machine). Le POM et les sources legacy
  sont inchanges, ce que verifie `git status`.

## Portes Gherkin

Le runner Cucumber-JVM est installe derriere le profil Maven dedie `cucumber` : la porte
par defaut n'execute pas les 88 scenarios. Sous `-Pcucumber` (plan 39), les step definitions de
`features/step_definitions/` demarrent l'artefact package (`target/quarkus-app/quarkus-run.jar`)
dans une JVM fille, contre une base PostgreSQL reelle et des bouchons derives des contrats geles,
et pilotent l'API `/v1` par REST Assured. Les scenarios realloues aux applications soeurs sont
etiquetes et exclus ; l'allocation complete est dans `GHERKIN_ALLOCATION.md`.

La suite exige un serveur PostgreSQL dont le compte peut creer des bases, designe par
`HORIZON_CUCUMBER_DB_URL` (base d'administration, par exemple
`jdbc:postgresql://localhost:5432/postgres`), `HORIZON_CUCUMBER_DB_USER` et
`HORIZON_CUCUMBER_DB_PASSWORD` ; a defaut, `localhost:5432/postgres` avec l'utilisateur du systeme.
La CI fournit un conteneur de service `postgres:16-alpine`.

```bash
mvn -f souscription-service/pom.xml clean test                       # porte par defaut, plans 01-35
mvn -f souscription-service/pom.xml clean verify -Pcucumber          # porte complete, plans 36+
mvn -f souscription-service/pom.xml verify -Pcucumber -Dcucumber.filter.name="persistance"
```

Le rapport est produit dans `souscription-service/target/cucumber-report.html`. Les
artefacts de presentation non repris par ce service sont inventories dans
`OUT_OF_SCOPE_VIEWS.md`.

# Runbook d'exploitation (W8-T07, plan 43)

Cette partie est le runbook d'exploitation du service. Elle se lit avec
`GO_LIVE_CHECKLIST.md`, qui porte la decision de mise en service et la couverture des 15 composants
cibles. Toutes les commandes s'executent depuis la racine du depot.

## 1. Boucle de developpement (development loop)

```bash
mvn -f souscription-service/pom.xml quarkus:dev     # live reload + Dev Services PostgreSQL
```

- `quarkus:dev` active le profil Quarkus `%dev` : rechargement a chaud, Dev Services PostgreSQL
  (`postgres:16-alpine`, moteur de conteneurs requis), migrations Flyway appliquees au demarrage
  (`%dev.quarkus.flyway.migrate-at-start=true`), port HTTP 8080, journaux en texte.
- **Aucune URL de base fixe** : la propriete source `horizon.baseUrl`
  (`http://127.0.0.1:${http.port}/horizon`, POM du monolithe) n'a pas d'equivalent. L'API est servie
  sous `/v1` a la racine du port ; l'URL publique est celle de `souscription-gateway` (`/api/v1`).
- `%dev` et `%test` sont **deux profils distincts** : `%test` (porte de build) a ses propres cles
  (port aleatoire `quarkus.http.test-port=0`, datasource inactive par defaut et armee par
  `PostgresDevServicesProfile`, OTel coupe, planificateur desactive, clients REST pointes sur les
  bouchons de contrat). Une valeur reglee pour `%dev` n'a aucun effet sur les tests, et inversement.
- Variante sans Docker : `mvn -f souscription-service/pom.xml -Plocal-h2 quarkus:dev` (H2 en
  memoire, voir la section « Commandes » ci-dessus) ou base PostgreSQL de developpement dediee via
  `HORIZON_DB_URL` / `HORIZON_DB_USER` / `HORIZON_DB_PASSWORD` et
  `-Dquarkus.datasource.devservices.enabled=false`.
- L'ancienne commande `mvn verify -Pintegration` du monolithe (Cargo + Tomcat telecharge) **ne
  s'applique pas** a ce module : ni Cargo, ni Tomcat n'interviennent dans son build.

## 2. Commandes de test (test commands)

```bash
mvn -f souscription-service/pom.xml clean test                  # unit + @QuarkusTest
mvn -f souscription-service/pom.xml clean verify -Pcucumber     # full gate incl. Gherkin
mvn -f souscription-service/pom.xml verify -Pcucumber -Dcucumber.filter.name="validation"
```

- **La porte de build du module est `mvn -f souscription-service/pom.xml clean verify -Pcucumber`.**
  Elle enchaine surefire (`*Test.java`), failsafe (`*IT.java`, dont `InterApplicationContractIT` et
  `StartupTimeIT`) et la suite Cucumber (`CucumberSuiteIT`). Une merge request n'est acceptable que
  si cette porte est verte.
- `clean test` reste la porte rapide (tests unitaires et `@QuarkusTest`).
- `-Dcucumber.filter.name="<motif>"` restreint la suite Gherkin aux scenarios dont le titre
  correspond ; le filtre de tags `not @hors-perimetre-souscription-service` reste applique.
- Prerequis : moteur de conteneurs pour Dev Services (avec Colima, exporter
  `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` et
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`, sinon Testcontainers ne trouve pas
  le moteur et les `@QuarkusTest` echouent) ; serveur PostgreSQL designe par
  `HORIZON_CUCUMBER_DB_URL` / `_USER` / `_PASSWORD` pour la suite Cucumber (section « Portes
  Gherkin »).
- Rapports : `target/surefire-reports/`, `target/failsafe-reports/`,
  `target/cucumber-report.html`.

## 3. Migration Flyway controlee (controlled Flyway migration)

- En production, **`quarkus.flyway.migrate-at-start=false`** : le service ne modifie jamais le
  schema `subscriptions` au demarrage. Seuls `%dev` et `%test` migrent au demarrage, sur des bases
  jetables.
- La migration est une **etape de pipeline approuvee manuellement** : job `migrate` de
  `.github/workflows/souscription-service.yml`, environnement protege
  `souscription-service-database` (approbation obligatoire), declenche apres le job `build` sur la
  branche par defaut uniquement. Aucune migration automatique au merge.
- **Verification prealable (pre-flight) — `flyway info`** : avant d'approuver l'etape, l'operateur
  confirme la version courante de l'environnement et les migrations en attente :

  ```bash
  mvn -f souscription-service/pom.xml -B org.flywaydb:flyway-maven-plugin:info \
    -Dflyway.url="$FLYWAY_URL" -Dflyway.user="$FLYWAY_USER" -Dflyway.password="$FLYWAY_PASSWORD" \
    -Dflyway.locations=filesystem:souscription-service/src/main/resources/db/migration
  ```

  Memes parametres que la commande `migrate` ci-dessous, afin de lire la meme table d'historique.
  Attendu : toutes les versions appliquees en etat `Success`, aucune en `Failed`, et la liste
  `Pending` identique a celle annoncee dans la merge request (`V1__subscriptions.sql`,
  `V2__subscription_draft.sql` a ce jour).
- **Commande de migration** (executee par le job `migrate`, identifiants issus des secrets
  `SOUSCRIPTION_DB_URL` / `SOUSCRIPTION_DB_USER` / `SOUSCRIPTION_DB_PASSWORD`, jamais en clair) :

  ```bash
  mvn -f souscription-service/pom.xml -B org.flywaydb:flyway-maven-plugin:migrate \
    -Dflyway.url="$FLYWAY_URL" -Dflyway.user="$FLYWAY_USER" -Dflyway.password="$FLYWAY_PASSWORD" \
    -Dflyway.locations=filesystem:souscription-service/src/main/resources/db/migration
  ```

  Le service valide le schema au demarrage (`quarkus.flyway.validate-on-migrate=true`) : deployer
  l'image avant que la migration soit appliquee n'altere pas les donnees.
- **Position de retour arriere : les migrations sont forward-only et ne sont jamais revertees.**
  Il n'existe ni `flyway undo`, ni script `DROP` manuel, ni re-baseline du schema : chacune de ces
  operations perd des donnees. En cas de migration defectueuse, on emet une **migration
  corrective** (`V<n+1>__...sql`) qui passe par la meme etape approuvee. Une migration en etat
  `Failed` est analysee via `flyway info`, puis corrigee par migration corrective ; `flyway repair`
  n'est utilise que pour nettoyer l'historique d'une migration qui n'a rien applique, sur decision
  explicite.
- Le retour arriere applicatif (section 5) **ne touche pas au schema** : `V1`/`V2` sont additives et
  le monolithe source n'utilise pas le schema PostgreSQL `subscriptions` (il ecrit dans sa propre
  base H2 embarquee).

## 4. Publication et diff OpenAPI (OpenAPI publication and diff)

- L'artefact fournisseur publie est **`target/openapi/openapi.json`** (et `openapi.yaml`), genere a
  la construction par SmallRye OpenAPI (`quarkus.smallrye-openapi.store-schema-directory`) et expose
  a l'execution sur `/q/openapi`. Le pipeline l'archive (`souscription-service-openapi`,
  `souscription-service-provider-contract`).
- Le pipeline **echoue sur toute difference cassante** entre ce document et le contrat gele
  `global/contracts/souscription-gateway-to-souscription-service/openapi.yaml` (etape « Publish
  OpenAPI document and diff against the gateway contract » : operationId, methode, chemin et
  ensemble des statuts compares), en plus de `OpenApiContractTest`, `ContractRepublishTest` et de la
  porte `InterApplicationContractIT`.
- **`/v1` est cluster-interne** (serveurs `https://souscription-service.<env>.svc.cluster.local:8443/v1`,
  mTLS, entree restreinte a `souscription-gateway` par `deploy/network-policy.yaml` et
  `deploy/authorization-policy.yaml`). **`/api/v1` est la facade publique** tenue par
  `souscription-gateway`, qui la route vers `/v1`. Strategie de version : `API_VERSIONING.md`.
- Un changement de contrat se fait d'abord dans `global/contracts/` (hors de ce module), puis dans
  le service ; jamais l'inverse.

## 5. Retour arriere de deploiement (deployment rollback)

- **La mise en service est une bascule progressive du routage de la gateway** : `souscription-gateway`
  route `/api/v1` vers ce service (`/v1`) par paliers de trafic, a la place du monolithe source.
- **Le monolithe source reste deployable** (WAR Struts/Tomcat `target/horizon.war`, construit par
  `mvn -DskipTests package` a la racine avec un JDK 8 ; `src/` et `pom.xml` racine non modifies)
  **jusqu'a validation complete de cette vague 8**. Il ne doit pas etre decommissionne avant.
- **Le retour arriere consiste a rebasculer le routage de la gateway** vers le monolithe : aucun
  redeploiement de ce service, aucune operation de schema (section 3). Les brouillons en cours
  (`subscription_draft`, TTL 20 minutes) sont perdus a la bascule, comme l'etaient les sessions
  HTTP du monolithe a son redemarrage ; les souscriptions confirmees par ce service restent dans
  `subscriptions.subscriptions` (PostgreSQL) et ne sont pas visibles du monolithe, qui ecrit dans sa
  base H2 : elles ne sont ni supprimees ni migrees par la rebascule.
- Ordre impose (mitigation R-002, `deploy/STATELESSNESS.md`) : l'affinite de session de la gateway
  n'est retiree **qu'apres** le test de charge du plan 40 (`deploy/BENCHMARK_REPORT.md`).
- **Point de decision Go/No-Go** : avant le premier palier de routage, sur la base de
  `GO_LIVE_CHECKLIST.md`. Toute ligne non verte — en particulier la validation DPO, **bloquante** —
  vaut No-Go. Le meme point est rejoue a chaque palier : erreurs 5xx, latence p95 ou alertes
  (`deploy/alerts/`) hors seuils declenchent la rebascule du routage.
