# Observabilite de souscription-service — journaux correles (NFG-014)

Plan `24-souscription-service-json-logs-correlation` (W5-T01).

## Seuil mesurable

| | Valeur |
|---|---|
| Base de reference (source Struts) | lignes non structurees emises par `ServletContext.log(...)` et `slf4j-simple`, **0 champ de correlation** |
| Cible | **100 % des lignes de journal en JSON, portant `traceId` et `correlationId`** |

La cible est verifiee par l'assertion de presence de champs de
`src/test/java/fr/demo/souscription/observability/CorrelatedLoggingTest.java` (chaque ligne emise
est formatee par le formateur JSON de Quarkus, reparsee, puis controlee) et par
`CorrelatedLoggingParityTest` (en-tete entrant honore et renvoye, generation en son absence,
absence de donnee personnelle, MDC non fuyant entre deux requetes), complete par un
echantillon parse par le collecteur de l'environnement de deploiement.

Deux prerequis de configuration rendent cette verification valide :

- `quarkus.log.console.json.fields.mdc.enabled=true` : sans cette propriete le MDC n'est pas
  serialise et les champs de correlation, pourtant poses, n'apparaissent pas dans la sortie.
- `%test.quarkus.otel.traces.sampler=always_on` : le ratio de production
  (`OTEL_SAMPLING_RATIO:0.1`) ferait passer ou echouer au hasard toute assertion de correlation.
  Tout test futur de ce module qui porte sur une trace doit conserver cet echantillonnage.

## Mecanisme

- Sortie console JSON : `quarkus.log.console.json=true`, format ECS, champ supplementaire
  `service` / `service.name` = `souscription-service`.
- `observability/CorrelationFilter` (`@Provider`, `ContainerRequestFilter` +
  `ContainerResponseFilter`) lit l'en-tete `X-Correlation-Id` propage par `souscription-gateway`,
  genere un UUIDv4 quand il est absent ou non conforme, pose `correlationId`, `traceId` et
  `spanId` dans le MDC, renvoie l'en-tete sur la reponse puis vide le MDC.
- `traceId` / `spanId` proviennent du contexte OpenTelemetry de la requete
  (extension `quarkus-opentelemetry`).
- Le corps `application/problem+json` porte le meme `correlationId` que les lignes de journal de
  la requete et que l'en-tete de reponse.
- Niveau pilotable par environnement : `HORIZON_LOG_LEVEL` pour la categorie
  `fr.demo.souscription`.

## Donnees personnelles

Aucune ligne de journal ne contient `firstName`, `lastName`, `email` ni `birthDate` : un brouillon
est journalise par son `draftId` seul (prerequis de l'ecart CFG-011, traite par le plan
`35-souscription-service-rgpd-draft-compliance`). `System.out`, `System.err` et toute
journalisation via un contexte de servlet sont interdits dans le module.

---

# Sondes de sante et arret gracieux (NFG-012)

Plan `25-souscription-service-health-probes` (W5-T02 / W5-T05).

## Seuil mesurable

| | Valeur |
|---|---|
| Base de reference (source Struts) | **0 point de sante expose**, arret abrupt a la destruction du contexte web |
| Cible | **3 sondes cablees** (startup, liveness, readiness) et **0 requete perdue pendant un redemarrage progressif** |

Mesure : statut des sondes sous injection de panne (base rendue injoignable) et nombre d'erreurs
cote client pendant un redemarrage progressif sous charge. Les deux mesures sont executees au
plan 40 ; la premiere est deja verifiee hors deploiement par `HealthProbeDownTest`.

## Mecanisme

- `quarkus.smallrye-health.root-path=/q/health` : `/q/health/started`, `/q/health/live` et
  `/q/health/ready`.
- `health/DatabaseReadinessCheck` (`@Readiness`) emprunte une connexion au pool Agroal et execute
  `SELECT 1`. Le datasource est injecte en `Instance<AgroalDataSource>` : le bean est inactif
  lorsque `quarkus.datasource.active=false`, et une injection directe ferait echouer le demarrage
  sur `InactiveBeanException` au lieu de rendre la sonde `DOWN`.
- `health/DependencyReadinessCheck` (`@Readiness`) est le point d'extension des dependances
  catalogue et simulation, renseigne au plan 30 (marqueur `TODO(plan 30)`). Il renvoie `UP`
  inconditionnellement : depuis le plan 41 seuls les clients REST distants existent, leur
  indisponibilite est rendue en 503 par appel et ne retire pas le pod du service.
- `observability/StartupObserver` observe `StartupEvent` et emet la ligne JSON
  `souscription-service pret : schema=…, pool=…`. Il remplace le bootstrap servlet de la source
  (`web/Application`, 84 lignes) : enregistrement du pilote JDBC, repertoire de donnees,
  initialisation du schema et publication des services dans le contexte web ne sont pas portes
  (Agroal, Flyway et ArC en sont proprietaires, cf. `CONFIG_MAPPING.md`). `src/main/webapp/META-INF/context.xml`
  et `src/main/tomcat/server.xml` ne sont pas portes non plus.
- `quarkus.shutdown.timeout=30S` : les requetes en vol sont drainees avant la fermeture du serveur.
  `deploy/deployment.yaml` fixe `terminationGracePeriodSeconds: 45` (strictement superieur au
  timeout, sinon le drainage est coupe par SIGKILL), un `preStop` de 5 s pour laisser le retrait
  des Endpoints se propager, et `maxUnavailable: 0`.
- Declarations de sondes : `deploy/deployment.yaml` (`startupProbe`, `livenessProbe`,
  `readinessProbe`). Les sondes y sont cablees sur les trois chemins, pas seulement exposees.

## Exposition des points de sante

`/q/health/*` (comme `/q/metrics` et `/q/openapi`) est servi sur le port HTTP du conteneur
(`containerPort: 8080`) et n'est appele que par le kubelet, via les trois sondes ci-dessus. Aucun de
ces chemins n'est route publiquement : `souscription-gateway` est le seul point d'entree externe et
sa liste d'acces ne publie que `/api/v1/*` — `/q/*` n'y figure pas et ne doit jamais y etre ajoute.
La charge utile de readiness enumere les composants internes (le check `database`, puis les
dependances catalogue et simulation au plan 30) : publiee, elle constituerait une surface de
reconnaissance. L'acces direct au service depuis l'exterieur du cluster est par ailleurs ferme par
le mTLS du plan 29.

## Scenarios Gherkin reecrits sur les sondes

Les trois scenarios encore pertinents de
`features/initialisation-application/initialisation-application.feature` sont desormais testes
sous cette forme — c'est leur unique forme d'execution :

| Scenario source | Forme cible | Ou il est verifie |
|---|---|---|
| `Demarrage nominal de l'application` | `/q/health/started` et `/q/health/ready` repondent 200 `UP`, la readiness nomme le check `database`, et la ligne JSON de demarrage est presente | `HealthProbeTest` (+ `CorrelatedLoggingTest` pour le format de la ligne) |
| `Acces aux services avant initialisation` | `/q/health/ready` est `DOWN` (503) et `/v1/*` n'est pas servi tant que la readiness n'est pas atteinte — le pod est retiré du Service | `HealthProbeDownTest` |
| `Arret de l'application` | arret gracieux : aucune requete en vol perdue dans la limite de `quarkus.shutdown.timeout` | `deployment.yaml` + mesure de redemarrage progressif du plan 40 |

Le cas nominal exige une base reelle (Dev Services PostgreSQL) et est ignore sans daemon de
conteneurs, comme `DatasourceSmokeTest` ; le cas d'indisponibilite n'exige aucun conteneur.

Les cinq autres scenarios du meme domaine sont sans objet et deja consignes comme tels, avec leur
justification, dans `src/main/resources/CONFIG_MAPPING.md` (section « Scenarios Gherkin sans
objet ») : `Les permissions du repertoire de donnees sont restreintes`, `Le chemin de la base est
lu depuis la propriete systeme`, `Le systeme de fichiers ne supporte pas les permissions POSIX`,
`Echec d'initialisation de l'application`, `Echec de liberation d'un pilote JDBC a l'arret`. Les
huit scenarios du domaine sont donc tous traites : 3 reecrits, 5 sans objet.

---

# Metriques Prometheus (NFG-015)

Plan `26-souscription-service-metrics-tracing` (W5-T03).

## Seuil mesurable

| | Valeur |
|---|---|
| Base de reference (source Struts) | **0 metrique exposee** — aucun point de collecte, aucun compteur, aucun histogramme |
| Cible | **point `/q/metrics`** portant le jeu Prometheus par defaut **plus >= 4 compteurs metier** (5 presents), et un **SLI de disponibilite 99,9 % mesurable** |

Verification : un grattage de `/q/metrics` (`MetricsEndpointTest`, `MetricsEndpointOfflineTest`) et
une revue du tableau de bord et des regles d'alerte avec l'exploitation.

## Mecanisme

- `quarkus.micrometer.enabled=true`, `quarkus.micrometer.export.prometheus.path=/q/metrics`,
  binders `http-server` et `jvm` armes. Le pool est deja instrumente par
  `quarkus.datasource.metrics.enabled=true` (W1-T02).
- Familles par defaut attendues : `http_server_requests_seconds*`, `jvm_memory_*`,
  `agroal_*` (dont `agroal_active_count`).
- `observability/BusinessMetrics` est l'**unique** declaration des noms des compteurs metier.
  Aucun autre composant n'appelle `MeterRegistry.counter("horizon…")` : un second meter sous un
  nom voisin rendrait les tableaux de bord faux sans faire echouer le build.

| Compteur (registre) | Serie Prometheus | Emetteur |
|---|---|---|
| `horizon.drafts.created` | `horizon_drafts_created_total` | `DraftService#start` |
| `horizon.confirmations.succeeded` | `horizon_confirmations_succeeded_total` | `ConfirmationService#confirmIdempotent` (ecriture reussie) |
| `horizon.confirmations.duplicate` | `horizon_confirmations_duplicate_total` (etiquette `path` = `read` / `conflict`) | `ConfirmationService`, les deux chemins idempotents du plan 23 |
| `horizon.validation.rejected` | `horizon_validation_rejected_total` | `ConstraintViolationMapper` uniquement |
| `horizon.draft.purged` | `horizon_draft_purged_total` | `DraftRetentionJob` (incremente du nombre de lignes supprimees) |

Deux choix de conception portent la justesse de ces mesures :

- le rejet de validation est compte **dans le mapper** et jamais dans les ressources : tout chemin
  de rejet passe par le mapper, le compte est donc exact et sans double compte ;
- les cinq compteurs sont **pre-declares au demarrage** (`@Startup` + `@PostConstruct`) : un
  compteur Micrometer n'existe qu'a sa premiere incrementation, et une serie absente serait
  indistinguable d'un code jamais atteint — les requetes PromQL renverraient « no data » au lieu
  de zero et l'alerte de purge inactive ne se declencherait jamais.

## Tableau de bord et regles d'alerte

- `deploy/dashboards/souscription-service.json` : SLI de disponibilite, taux d'erreur par classe de
  statut, p95 / p99 et consommation du budget de latence (300 ms), compteurs metier, occupation et
  latence d'acquisition du pool, accumulation de brouillons, memoire JVM.
- `deploy/alerts/souscription-service-rules.yaml` : SLI 99,9 % calcule a partir de
  `http_server_requests_seconds_count` partitionne par classe de statut, budget de latence 300 ms,
  taux d'erreur, les deux regles de pool de `POOL_ALERTS.md` (R-003) et les deux regles de
  brouillons de `DRAFT_ALERTS.md` (R-002).

Les 4xx sont volontairement exclues du SLI de disponibilite : validation refusee et brouillon
expire sont des reponses correctes du service ; les compter ferait sonner l'alerte sur un trafic
sain.

---

# Tracage distribue (NFG-016)

Plan `26-souscription-service-metrics-tracing` (W5-T04).

## Seuil mesurable

| | Valeur |
|---|---|
| Base de reference (source Struts) | **0 trace** — aucune correlation entre les hops, latence non attribuable |
| Cible | **100 % des requetes en erreur tracees de bout en bout**, latence attribuable par hop |

Verification : inspection d'une trace de confirmation dans le backend de tracage — elle doit porter
le span serveur HTTP, les spans JDBC/Hibernate et les spans clients sortants du meme `traceId`.

## Mecanisme

- `quarkus.otel.enabled=true`, export OTLP vers `${OTEL_EXPORTER_OTLP_ENDPOINT}` (aucune valeur
  par defaut en production, NFG-004 ; le profil `test` fixe une adresse locale et desarme le SDK).
- Propagation **W3C `traceparent`** : en entree depuis `souscription-gateway`, en sortie par les
  clients REST Quarkus (plans 27-28) — la propagation est automatique des que OTel est actif,
  aucun code de propagation n'est ecrit ; l'assertion correspondante est posee au plan 30.
- Spans JDBC / Hibernate automatiques via l'extension : le repository n'est pas instrumente a la
  main.
- `traceId` / `spanId` sont poses dans le MDC et serialises dans chaque ligne JSON (W5-T01), ce
  qui relie journaux et traces.

### Conservation des traces en erreur

L'echantillonnage de tete est `parentbased_traceidratio` avec
`quarkus.otel.traces.sampler.arg=${OTEL_SAMPLING_RATIO:0.1}` : la decision du gateway est
respectee, et 10 % des traces racines sont echantillonnees.

Un echantillonneur de tete **ne peut pas** conserver selectivement les erreurs : au premier span,
le statut de la requete n'est pas encore connu. Le mecanisme retenu est donc le **tail sampling du
collecteur OTLP**, seul endroit ou la trace est complete au moment de la decision. Le service
exporte vers le collecteur, et le collecteur arbitre :

```yaml
processors:
  tail_sampling:
    decision_wait: 10s
    policies:
      - name: toutes-les-erreurs
        type: status_code
        status_code: { status_codes: [ERROR] }
      - name: requetes-lentes
        type: latency
        latency: { threshold_ms: 300 }
      - name: echantillon-nominal
        type: probabilistic
        probabilistic: { sampling_percentage: 10 }
```

Consequence sur la configuration du service : le ratio de tete doit valoir **1.0**
(`OTEL_SAMPLING_RATIO=1.0`) dans les environnements ou le tail sampling est actif, sinon 90 % des
traces en erreur sont jetees avant d'atteindre le collecteur et aucune politique ne peut les
rattraper. Le defaut `0.1` du fichier ne s'applique qu'a un deploiement sans collecteur a
politique.

## NFG-011 — mesure en execution multi-replicas

Premiere mesure des trois criteres de `STATELESSNESS.md` en conditions reelles :
**N instances sans affinite de session**, **0 etat par utilisateur en memoire**, **nombre de lignes
de `subscription_draft` stable**. Les deux premiers criteres sont structurels (le brouillon est en
base depuis la vague 2) ; le troisieme est surveille par le panneau d'accumulation du tableau de
bord et l'alerte `SouscriptionServiceDraftAccumulation`.

## Reversibilite

Cette vague est reversible **par configuration seule**, sans aucune modification de comportement
metier :

- `quarkus.micrometer.enabled=false` : plus aucune metrique exposee ;
- `quarkus.otel.enabled=false` : plus aucune trace exportee (les champs `traceId` / `spanId` des
  journaux deviennent vides, la correlation par `correlationId` subsiste).

Aucun chemin metier ne depend d'un compteur ni d'un span : les increments sont des effets de bord
hors transaction, et le retrait des deux extensions laisse le contrat d'API inchange.

Cette reversibilite est verifiee par la suite : `mvn clean test
-Dquarkus.otel.enabled=false -Dquarkus.micrometer.enabled=false` passe. Deux points la rendent
effective :

- `observability/BusinessMetrics` resout le `MeterRegistry` par une `Instance<MeterRegistry>` et
  retombe sur un registre local non expose quand aucun n'est produit. Une injection directe
  rendrait l'instrumentation **porteuse du demarrage** : avec `micrometer.enabled=false`,
  l'application ne demarrerait plus (`UnsatisfiedResolutionException`), ce qui n'est pas un chemin
  de retour arriere ;
- les seules assertions qui dependent de l'instrumentation (series de `/q/metrics`, `traceId` du
  MDC) sont neutralisees par `@EnabledUnlessPropertyFalse` sur ces deux proprietes. Aucune
  assertion metier n'observe de meter ni de span.
