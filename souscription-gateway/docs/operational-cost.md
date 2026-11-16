# Coût opérationnel du module souscription-gateway (NFG-022)

## 1. Ce qui remplace quoi

| Avant (source) | Après (cible) |
| --- | --- |
| WAR déployé sur Tomcat 10 via le plugin Cargo Maven 3 (profil `integration` du `pom.xml` racine) | Image conteneur JVM fast-jar `souscription-gateway` sur UBI9 OpenJDK 21, exécution non-root UID 185 |
| Descripteur serveur Tomcat sous `src/main/tomcat/` | `src/main/resources/application.properties` + manifestes `deploy/` |
| Aucune chaîne CI : build local uniquement, aucun contrôle de vulnérabilité | 5 pipelines CI de module, chacun avec un contrôle de dépendances et un contrôle d'image |

Le profil `integration` et le plugin Cargo Maven 3 du `pom.xml` racine sont **conservés intacts** : ils
constituent la frontière de rollback de la vague 0. Le module `souscription-gateway` n'y fait aucune
référence.

## 2. Coût : cinq pipelines au lieu d'un build sans CI

La topologie cible découpe l'application source en cinq déployables, chacun avec son propre pipeline
de module :

- `souscription-web` (SPA Angular)
- `souscription-gateway` (ce module)
- `souscription-service`
- `souscription-simulation-service`
- `souscription-catalogue-service`

Le coût opérationnel passe donc d'un build unique sans CI à cinq pipelines à maintenir, chacun avec
les mêmes six étapes. Pour `souscription-gateway` (`.ci/pipeline.yml`) :

| Étape | Commande | Condition d'échec |
| --- | --- | --- |
| `build` | `mvn -f souscription-gateway/pom.xml clean package` | échec de compilation / packaging |
| `test` | `mvn -f souscription-gateway/pom.xml verify` | test en échec |
| `dependency-scan` | `mvn -f souscription-gateway/pom.xml org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7` | toute CVE HIGH/CRITICAL sur l'arbre de dépendances **résolu** |
| `image-build` | `docker build -f souscription-gateway/src/main/docker/Dockerfile.jvm -t souscription-gateway:$CI_COMMIT_SHA souscription-gateway` | échec de construction de l'image |
| `image-scan` | scan de l'image (`trivy`) | toute vulnérabilité HIGH/CRITICAL, OS ou applicative |
| `push` | `docker push souscription-gateway:$CI_COMMIT_SHA` | échec de publication |

Conséquences à assumer : cinq jeux de secrets de registre, cinq bases de données de vulnérabilités à
rafraîchir, cinq versions d'image à corréler lors d'une livraison coordonnée, et une mise à jour de la
plateforme Quarkus à propager cinq fois.

## 3. Propriétaires

| Élément | Propriétaire | Fichier / plan |
| --- | --- | --- |
| Matériel mTLS (keystore / truststore, rotation, CA interne) | équipe infrastructure / plateforme | `deploy/deployment.yaml` (bloc `volumeMounts` commenté), renseigné au plan 19 |
| Isolation réseau des trois services aval (CFG-011 / NFG-004) | équipe infrastructure / plateforme | `deploy/networkpolicy.yaml` |
| Ingress partagé et terminaison TLS publique (modèle same-origin, pas de CORS) | équipe infrastructure / plateforme | hors module |
| Pipeline CI du module, Dockerfile, manifestes applicatifs | équipe applicative souscription-gateway | `.ci/pipeline.yml`, `src/main/docker/`, `deploy/` |
| Endpoints de santé câblés aux sondes | équipe applicative souscription-gateway | plan 25 |

## 4. Mesures de référence

Mesures prises sur le fast-jar `target/quarkus-app/quarkus-run.jar`, JDK 21 (Temurin 21.0.11),
`-XX:MaxRAMPercentage=70`, profil `prod`, sans conteneur (aucun runtime Docker/Podman disponible sur
la machine de mesure — les chiffres sont donc les chiffres JVM, l'image UBI9 ajoute un écart
négligeable sur le temps de démarrage).

| Indicateur | Cible | Référence source | Mesure |
| --- | --- | --- | --- |
| Démarrage jusqu'à readiness | < 1,5 s | 3–8 s (Tomcat 10 + WAR) | **0,592 s** (`started in 0.592s`, `GET :9000/q/health/ready` → HTTP 200) |
| RSS au repos | ≤ 180 Mo | — | **123,7 Mo** (mesuré 30 s après le démarrage, sans trafic) |
| Latence de `/q/health/ready` | — | — | 1,1–2,8 ms |

Les deux cibles sont tenues. À revalider dans le conteneur dès qu'un runtime OCI est disponible dans
l'environnement de build (étape `image-build` du pipeline).

## 5. Sondes, arrêt gracieux et mise à jour progressive (W6-T01)

Configuration : `quarkus.management.port=9000` (sondes `/q/health/live` et `/q/health/ready` servies
**uniquement** sur l'interface de management, `404` sur le port public), `quarkus.shutdown.timeout=10s`
(> 5000 ms, plus long délai de lecture aval — `subscription`), `terminationGracePeriodSeconds: 20`,
`preStop` `sleep 5`, `replicas: 2`, HPA `minReplicas: 2` / `maxReplicas: 6` (CPU + débit par pod),
PDB `maxUnavailable: 1`. La santé des avals (`downstream`, `/q/health/well`) est informative : elle
publie l'état des disjoncteurs et reste toujours UP.

Mesures du 2026-11-13, `bash src/test/scripts/rolling-update-check.sh` (fast-jar, JDK 21.0.11, doublures
WireMock issues des fixtures de contrat, hôte macOS 15 cœurs / 24 Go, sans conteneur) :

| Indicateur | Cible | Mesure | Verdict |
| --- | --- | --- | --- |
| Drain sous charge : requêtes en vol coupées au SIGTERM | 0 | **0** (10/10 requêtes longues de 2 s terminées en 200 ; 318 requêtes de charge acceptées avant SIGTERM, 0 échec, 0 connexion coupée) | OK |
| SIGTERM → fin du processus | ≤ 10 s | **2,083 s** (« All HTTP requests complete » journalisé) | OK |
| Mise à jour progressive de 2 répliques sous charge (maxSurge 1, maxUnavailable 0, preStop 2 s) | 0 requête en échec | **0 / 887** ; drains 250 ms et 630 ms | OK |
| Démarrage jusqu'à readiness, instance isolée | 0,6–1,5 s | **0,837 s** (`started in 0.837s`, `GET :mgmt/q/health/ready` → 200) ; référence plan 04 : 0,592 s | OK |
| Démarrage jusqu'à readiness, répliques lancées pendant la campagne de charge | — | 1,51 s – 5,23 s (`started in` 1,28 s – 3,32 s) : l'hôte porte simultanément la charge, les doublures et la réplique sortante | informatif |
| RSS au repos (30 s sans trafic, script) | 100–180 Mo | **139 Mo** (142 368 kB) ; 185 Mo au moment de la readiness | OK |
| RSS au repos, instance isolée après quelques appels de sonde | 100–180 Mo | 215 Mo | à revalider en conteneur |

Lecture du RSS : hors conteneur, `-XX:MaxRAMPercentage=70` s'applique aux 24 Go de l'hôte, donc le
tas peut grossir largement avant tout GC ; le chiffre varie d'une exécution à l'autre (139 Mo à 215 Mo).
Dans le pod, la limite `memory: 256Mi` borne le tas à environ 180 Mo et c'est cette mesure, en
conteneur, qui fait foi (étape `image-build` du pipeline dès qu'un runtime OCI est disponible).

Requêtes reçues **après** le SIGTERM pendant le test local : 52 (13 × 503 du filtre d'arrêt, 39
connexions refusées, 0 coupée). En cluster, aucune n'arrive : le `preStop` retire le pod des
endpoints du Service avant le SIGTERM.

Gates Cucumber (glue `steps/InitialisationApplicationSteps`, scénarios balisés
`@souscription-gateway`) : « Demarrage nominal de l'application » 1/1 (5 étapes) et « Arret de
l'application » 1/1 (3 étapes). Les six scénarios H2/POSIX de la fonctionnalité restent à
`souscription-service` et ne sont pas joués ici. L'apostrophe du nom est passée en `.` dans
`-Dcucumber.filter.name` (voir `docs/gherkin-runner.md`).

### Rejeu de la vérification de parité 25 (2026-11-13)

Même script, même hôte, après installation de `HealthProbeParityTest` (5/5 : liveness et readiness
UP pendant une panne réelle des trois avals avec disjoncteur catalogue OPEN, santé des avals UP avec
l'état des disjoncteurs en donnée et sans aucun appel aux doublures, sondes 200 sur le management et
404 sur le port public) :

| Indicateur | Cible | Mesure | Verdict |
| --- | --- | --- | --- |
| Drain sous charge : requêtes en vol coupées | 0 | **0** (10/10 requêtes longues en 200 ; 366 requêtes de charge acceptées avant SIGTERM, 0 échec, 0 coupée) | OK |
| SIGTERM → fin du processus | ≤ 10 s | **2,059 s** | OK |
| Mise à jour progressive de 2 répliques sous charge | 0 requête en échec | **0 / 1202** ; drains 203 ms et 125 ms | OK |
| Démarrage jusqu'à readiness (5 répliques) | 0,6–1,5 s | **930 – 1197 ms** (`started in` 0,704 – 0,997 s) | OK |
| RSS à la readiness | — | 184 Mo | informatif |
| RSS au repos (30 s sans trafic) | 100–180 Mo | 28 Mo | non représentatif |

Le RSS au repos de 28 Mo vient de la compression mémoire de macOS sur un processus inactif : ce
n'est pas une empreinte réelle de la JVM. Il ne remplace pas la mesure en conteneur, qui reste
celle qui fait foi (limite `256Mi`).
