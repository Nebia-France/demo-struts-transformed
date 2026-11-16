# Observabilite : metriques et SLI de disponibilite 99,9 % (W6-T02)

La source n'exposait aucune metrique : le seul signal etait le rendu (ou non) de la page. Ce document
decrit l'instrument de mesure de l'objectif de disponibilite confirme (99,9 %), mesure **a la
passerelle**.

## 1. Exposition

| Element | Valeur |
| --- | --- |
| Format | Prometheus (`quarkus-micrometer-registry-prometheus`) |
| Chemin | `/q/metrics` |
| Interface | **management, port 9000 uniquement** ; le port public 8080 repond 404 (`MetricsTest#metricsAreServedOnTheManagementPortOnly`) |
| Publication | jamais via l'ingress partage (aucune regle `/q/*`) ; le `Service` publie 9000 au sein du cluster pour le scraper (`deploy/service.yaml`) |

Exposer `/q/metrics` publiquement donnerait a un attaquant une carte interne (routes, dependances,
etats des disjoncteurs, dates d'expiration des certificats) : c'est pourquoi la seule interface est
celle de management.

## 2. Familles exposees

| Besoin | Metrique(s) | Etiquettes |
| --- | --- | --- |
| Temps de reponse par route (serveur) | `http_server_requests_seconds_{count,sum,bucket,max}` | `method`, `uri` (gabarit), `status`, `outcome` |
| Temps de reponse par aval (client REST) | `http_client_requests_seconds_{count,sum,bucket,max}` | `clientName` (hote aval), `method`, `uri` (gabarit `@Path` du client), `status`, `outcome` |
| Requetes pendantes par arete (pool) | `http_client_pending_requests` | `clientName` = `catalogue`, `simulation`, `subscription` (`PendingRequests`, plan 24) |
| Etat des disjoncteurs | `ft_circuitbreaker_state_current`, `ft_circuitbreaker_opened_total`, `ft_circuitbreaker_calls_total` | `method` (methode `*Gateway`), `state` |
| Tentatives / delais / cloisons | `ft_retry_retries_total`, `ft_retry_calls_total`, `ft_timeout_calls_total`, `ft_bulkhead_executionsWaiting`, `ft_bulkhead_calls_total` | `method`, resultat |
| Rejets d'arete | `gateway_rejections_total` | `status` (403, 405, 413), `reason` (`csrf-missing`, `csrf-mismatch`, `method`, `size`) |
| Expiration du materiel mTLS (plan 19) | `gateway_tls_certificate_expiry_seconds` (date `notAfter`, secondes epoch) | `client` (3 aretes), `store` (`keystore`, `truststore`) |
| JVM / processus | `jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `jvm_threads_*`, `process_cpu_usage` | standards Micrometer |
| Connexions entrantes | `vertx_http_connections_current`, `http_server_active_requests` | — |

Les histogrammes de latence utilisent dix bornes explicites (25 ms, 50 ms, 100 ms, 250 ms, 500 ms,
1 s, 2 s, 3 s, 5 s, 10 s ; `LatencyHistograms`) alignees sur les delais aval de 3 s / 5 s et sur le
drainage de 10 s, au lieu de l'histogramme de percentiles Micrometer (~70 seaux par serie).

### Compteurs de rejet

`RejectionMetrics#rejected(status, reason)` est appele par :

| Filtre | Statut | Raison |
| --- | --- | --- |
| `CsrfDoubleSubmitFilter` | 403 | `csrf-missing` si le cookie ou l'en-tete est absent / vide ; `csrf-mismatch` sinon (valeurs differentes, jeton trop long, jeton expire ou signature invalide) |
| `MethodAllowListFilter` | 405 | `method` |
| `BodySizeProblemFilter` | 413 | `size` (`Content-Length` declare au-dela de `quarkus.http.limits.max-body-size`) |

Aucune limitation de debit n'est activee sur la passerelle : il n'existe donc pas de serie 429. Les
quatre series connues sont enregistrees a 0 au demarrage, pour qu'une alerte sur leur taux ne lise
jamais une serie absente comme une absence de rejet. Le corps de la reponse 403 ne distingue
toujours pas les deux causes (plan 16) : la distinction n'existe que dans la metrique interne.

Limite connue : un corps transmis en `chunked` sans `Content-Length` et depassant la limite est
coupe par la couche HTTP de Vert.x avant tout filtre ; il apparait dans `http_server_requests` avec
le statut 413 mais n'incremente pas `gateway_rejections_total{reason="size"}`.

## 3. Cardinalite bornee

Aucune etiquette ne porte de valeur issue de la requete. Valeurs d'etiquette **interdites** (revue
de cardinalite, meme regle de non-exposition des donnees personnelles que les journaux et traces) :

| Valeur interdite | Pourquoi | Ou elle apparaitrait sans protection |
| --- | --- | --- |
| code produit (`PRUDENT`, ...) | cardinalite non bornee (chemin libre) | `uri` de `/api/v1/products/{code}` |
| identifiant de brouillon (UUID) | identifiant + une serie par brouillon | `uri` de `/api/v1/drafts/{id}/...` |
| cle de confirmation / reference de souscription (UUID) | identifiant metier | `uri` de `/api/v1/subscriptions/{key}`, en-tete `Idempotency-Key` |
| jeton CSRF | secret de session | cookie / en-tete, jamais lu par un binder ni par `RejectionMetrics` |
| chemin brut hors routes publiques | une serie par chemin aleatoire | `uri` d'une requete rejetee avant routage (regroupee sous `/unmatched`) |

Verification (executee par
`MetricsTest#noLabelValueCarriesARawIdentifier` et `MetricsParityTest#noIdentifierInAnyLabelValue`
(ce dernier verifie aussi qu'aucun jeton CSRF n'apparait dans le scrape), qui generent du trafic avec de vrais identifiants
puis parcourt toutes les valeurs d'etiquette du scrape) :

1. aucune valeur d'etiquette ne contient d'UUID (identifiant de brouillon, reference de
   souscription, cle de confirmation) ;
2. aucune valeur d'etiquette ne contient de code produit (`PRUDENT`, `EQUILIBRE`, `DYNAMIQUE`,
   `INCONNU`) ;
3. les routes apparaissent sous leur gabarit (`/api/v1/products/{code}`, `/api/v1/drafts/{id}`,
   `/api/v1/drafts/{id}/confirmation`, ...).

Mecanisme : `quarkus.micrometer.binder.http-server.match-patterns` (ordonne, le premier motif qui
correspond gagne, prioritaire sur le gabarit JAX-RS). Chaque sous-ressource de brouillon a son motif
propre ; le dernier motif regroupe sous `uri="/unmatched"` tout chemin qui n'est pas une route
publique (requete rejetee par un filtre d'arete avant routage, chemin inconnu). Sans lui, une rafale
de `PATCH /<aleatoire>` creerait une serie par chemin. Cote client, l'etiquette `uri` est le gabarit
`@Path` de l'interface REST (`/v1/products/{code}`, `/v1/drafts/{draftId}`, ...). Les autres
etiquettes viennent d'ensembles fermes : methodes `*Gateway`, noms d'aretes, statuts HTTP.

Toute nouvelle route doit soit etre couverte par un gabarit JAX-RS absent des motifs, soit recevoir
son propre motif **avant** le motif `/unmatched` ; sans cela elle serait comptee sous `/unmatched`.

## 4. SLI de disponibilite

**Definition** (ratio non-5xx mesure a la passerelle) : proportion des requetes de l'API publique traitees par la passerelle dont le statut
n'est pas 5xx, mesuree sur `http_server_requests_seconds_count` de la passerelle (et non sur les
services aval : une indisponibilite masquee par un repli reste un succes pour l'utilisateur, une
erreur de la passerelle elle-meme reste un echec).

```promql
# bonnes requetes / requetes totales, fenetre W
1 - (
  sum(rate(http_server_requests_seconds_count{job="souscription-gateway", status=~"5.."}[W]))
  /
  sum(rate(http_server_requests_seconds_count{job="souscription-gateway"}[W]))
)
```

- Les 4xx (400, 403, 404, 405, 409, 413, 415) sont des **succes** du point de vue du SLI : la
  passerelle a correctement refuse une requete invalide.
- Les 503 emis pendant un arret (drainage) ou par un disjoncteur ouvert, et les 502/504 des aretes
  aval, sont des **echecs** : ils consomment le budget.
- `/q/*` (sondes, metriques) n'est pas compte : ces URI sont exclues par Micrometer.

**Objectif (SLO)** : SLI >= 99,9 % sur 30 jours glissants. Budget d'erreur : 0,1 % des requetes,
soit 43 min 12 s d'indisponibilite totale equivalente sur 30 jours.

## 5. Alertes par taux de consommation du budget (`deploy/alerts.yaml`)

Methode multi-fenetres / multi-taux : une alerte ne se declenche que si la fenetre longue **et** la
fenetre courte depassent le seuil (la fenetre courte assure la retombee rapide une fois l'incident
resolu). Le taux de consommation est `ratio_erreurs / 0,001`.

| Alerte | Severite | Fenetre longue | Fenetre courte | Taux | Budget consomme |
| --- | --- | --- | --- | --- | --- |
| `GatewayAvailabilityBudgetBurnFast` | page | 1 h | 5 min | 14,4 | 2 % en 1 h |
| `GatewayAvailabilityBudgetBurnFast` | page | 6 h | 30 min | 6 | 5 % en 6 h |
| `GatewayAvailabilityBudgetBurnSlow` | ticket | 1 j | 2 h | 3 | 10 % en 1 j |
| `GatewayAvailabilityBudgetBurnSlow` | ticket | 3 j | 6 h | 1 | 10 % en 3 j |

Les ratios sont precalcules par des regles d'enregistrement
`gateway:http_server_errors:ratio_rate<fenetre>`.

Alertes complementaires (diagnostic, pas de SLO) :

| Alerte | Condition | Severite |
| --- | --- | --- |
| `GatewayCircuitBreakerOpen` | un disjoncteur `ft_circuitbreaker_state_current{state="open"} == 1` pendant 5 min | ticket |
| `GatewayDownstreamPoolSaturation` | `http_client_pending_requests` > 80 % du pool de l'arete (40 / 40 / 24) pendant 5 min | ticket |
| `GatewayEdgeRejectionSpike` | `sum by (reason) (rate(gateway_rejections_total[5m])) > 1` pendant 10 min | ticket |
| `GatewayLatencyP99High` | p99 d'une route > 3 s pendant 10 min | ticket |
| `GatewayMtlsCertificateExpiringSoon` | `gateway_tls_certificate_expiry_seconds - time() < 30 j` | ticket (J-30) |
| `GatewayMtlsCertificateExpiryCritical` | `gateway_tls_certificate_expiry_seconds - time() < 7 j` | page (J-7) |
| `GatewayMtlsCertificateExpiryMissing` | la jauge d'expiration est absente en production | ticket |

## 6. Tableau de bord (`deploy/dashboard.json`)

Tableau de bord Grafana (source de donnees Prometheus parametree `${datasource}`) :

1. disponibilite (SLI 30 j, budget restant, taux de consommation 1 h / 6 h) ;
2. debit et taux d'erreur par route (`uri`, `status`) ;
3. latence p95 / p99 par route ;
4. latence p95 / p99 par aval ;
5. etat des disjoncteurs par methode ;
6. saturation des pools (`http_client_pending_requests`) et des cloisons
   (`ft_bulkhead_executionsWaiting`) ;
7. rejets d'arete par raison ;
8. jours restants avant expiration des certificats mTLS ;
9. JVM (memoire utilisee, pauses GC).

## 7. Proprietaires

La collecte (ServiceMonitor / configuration de scrape du job `souscription-gateway` sur le port
`management`), le chargement de `alerts.yaml` dans Prometheus/Alertmanager et l'import du tableau de
bord dans Grafana relevent de l'equipe plateforme d'observabilite ; ce module fournit les
definitions versionnees.

## 8. Journal JSON correle, sans donnee personnelle (W6-T03)

La source ecrivait des traces en texte libre sur stdout (`slf4j-simple`,
`LOG.warn("CSRF token mismatch")`), sans correlation avec la requete. La passerelle ecrit desormais
**un enregistrement JSON par ligne** sur la console, dans tous les profils
(`quarkus-logging-json`).

### 8.1 Configuration (`application.properties`)

| Cle | Valeur | Raison |
| --- | --- | --- |
| `quarkus.log.console.json` | `true` | un objet JSON par ligne, quel que soit le profil |
| `quarkus.log.console.async` | `true` | un gestionnaire synchrone bloquerait la boucle d'evenements sous un flux soutenu (plan 22) |
| `quarkus.log.console.async.queue-length` | `1024` | file bornee ; au-dela, le comportement par defaut (`block`) s'applique : aucun enregistrement de rejet n'est perdu |
| `quarkus.log.console.json.additional-field."service".value` | `souscription-gateway` | identifie le service dans l'agregateur |
| `quarkus.log.console.filter` | `correlation-fields` | `CorrelationFieldsFilter` garantit les trois champs de correlation |

La JVM de test utilise le LogManager JBoss (`java.util.logging.manager` dans surefire / failsafe) :
`LogHygieneTest` verifie donc le format reellement produit.

### 8.2 Schema d'un enregistrement

```json
{
  "timestamp": "2026-11-13T19:25:10.377331+01:00",
  "sequence": 3061,
  "loggerClassName": "org.jboss.logging.Logger",
  "loggerName": "fr.demo.souscription.gateway.observability.RejectionMetrics",
  "level": "WARN",
  "message": "csrf rejection reason=csrf-missing status=403 route=/api/v1/drafts",
  "threadName": "vert.x-eventloop-thread-2",
  "threadId": 171,
  "mdc": { "traceId": "", "spanId": "", "requestId": "req-6bd8a26e-18df-4129-80f2-c5174f6ba6d2" },
  "ndc": "",
  "hostName": "...",
  "processName": "...",
  "processId": 91812,
  "service": "souscription-gateway"
}
```

| Champ | Origine | Toujours present |
| --- | --- | --- |
| `timestamp`, `sequence`, `level`, `loggerName`, `loggerClassName`, `message`, `threadName`, `threadId`, `ndc`, `hostName`, `processName`, `processId` | formatteur JSON Quarkus | oui |
| `service` | champ additionnel statique | oui |
| `mdc.traceId`, `mdc.spanId` | `quarkus-opentelemetry` pendant un span actif : identifiants du span serveur de la requete, egaux a ceux de la trace exportee (`TracingTest#logRecordsCarryTheIdsOfTheActiveSpan`, section 9.5) ; **vides** hors requete ou si le SDK est inactif (`%test`, `%dev`, levier de retour arriere) | oui (`CorrelationFieldsFilter`) |
| `mdc.parentId`, `mdc.sampled` | `quarkus-opentelemetry` pendant un span actif (parent du span, decision d'echantillonnage) | non |
| `mdc.requestId` | `RequestCorrelation` (`@RouteFilter` execute avant tout filtre d'arete) : en-tete `X-Request-Id` de l'ingress s'il respecte `[A-Za-z0-9._-]{1,128}`, sinon UUID genere. **Meme valeur que le membre `instance` du document problem+json** de la reponse. Vide hors requete (demarrage, arret) | oui (`CorrelationFieldsFilter`) |
| `exception` | absent : aucune exception n'est journalisee avec son message ou sa pile (voir 8.4) | non |

### 8.3 Une entree par rejet ou par echec

| Evenement | Emetteur | Niveau | Message |
| --- | --- | --- | --- |
| rejet CSRF (403) | `RejectionMetrics#rejected`, appele par `CsrfDoubleSubmitFilter` | `WARN` | `csrf rejection reason=<csrf-missing\|csrf-mismatch> status=403 route=<gabarit>` |
| methode refusee (405) | `RejectionMetrics#rejected`, appele par `MethodAllowListFilter` | `WARN` | `method rejection reason=method status=405 route=<gabarit>` |
| corps trop volumineux (413) | `RejectionMetrics#rejected`, appele par `BodySizeProblemFilter` | `WARN` | `size rejection reason=size status=413 route=<gabarit>` |
| echec aval ou interne (5xx) | `ProblemResponses#journalise` (point unique de construction des reponses d'erreur JAX-RS) | `ERROR` | `request failure status=<502\|503\|504\|500> cause=<chaine de classes>` |
| refus 4xx mappe (400, 404, 409...) | `ProblemResponses#journalise` | `DEBUG` | `request refused status=<4xx> cause=<chaine de classes>` |

Le compteur `gateway.rejections` et l'entree de journal sont alimentes **au meme endroit**
(`RejectionMetrics#rejected`) : le nombre d'entrees `rejection` est egal a la variation du compteur,
ce que `LogHygieneTest` verifie. Le gabarit de route suit les memes regles que les etiquettes `uri`
(section 3) : `/api/v1/drafts/{id}/subscriber`, ..., et `/unmatched` pour tout autre chemin
(`RouteTemplates`).

### 8.4 Champs interdits (forbidden fields: CSRF token, personal data)

Ne doivent **jamais** apparaitre dans un enregistrement, ni comme nom de champ, ni comme valeur :

| Interdit | Pourquoi | Protection |
| --- | --- | --- |
| valeur du jeton CSRF (cookie `XSRF-TOKEN`, en-tete `X-CSRF-Token`) | secret de session | le message de rejet ne porte que la raison (ensemble ferme) |
| nom, prenom, e-mail, date de naissance du souscripteur (`lastName`, `firstName`, `email`, `birthDate` et leurs valeurs) | donnees personnelles | aucun corps de requete ou de reponse n'est journalise ; les exceptions sont reduites a la chaine de leurs noms de classe (`ProblemResponses#causeChain`), car leur message peut citer un champ ou une valeur (chemin de violation de validation, erreur de desserialisation) |
| chemin brut, code produit, identifiant de brouillon, cle de confirmation | identifiants / cardinalite | gabarit de route uniquement (`RouteTemplates`) |
| valeur libre de `X-Request-Id` | injection de contenu arbitraire | seuls les identifiants `[A-Za-z0-9._-]{1,128}` sont repris, sinon un UUID est genere |

Verification : `LogHygieneTest` capture, avec le formatteur et le filtre du gestionnaire console
configure, la sortie produite pendant les requetes negatives de `features/securite-web` (jeton
absent, vide, faux, casse modifiee, de 101+ caracteres ; `DELETE`, `PATCH`, `OPTIONS`, `TRACE` ;
corps > 64 Kio) et pendant une saisie de souscripteur acceptee puis invalide. Il verifie que 100 %
des lignes sont du JSON portant `traceId`, `spanId` et `requestId`, exactement une entree par rejet
(egale a la variation de `gateway.rejections`), et 0 occurrence du jeton ou des donnees personnelles.

## 9. Traces distribuees (W6-T04)

Dans la source, une requete commencait et finissait dans une seule JVM : la pile d'appels etait
l'enregistrement causal complet. Apres le decoupage, seule la passerelle peut ancrer la trace qui le
remplace (CFG-012) : elle porte le span racine et propage le `traceparent` W3C aux trois services aval.

### 9.1 Configuration (`application.properties`)

| Cle | Valeur | Role |
| --- | --- | --- |
| `quarkus.otel.enabled` | `true` | extension active (build) |
| `quarkus.otel.sdk.disabled` | `false` ; `%test` et `%dev` : `true` | la suite et le mode dev ne dependent d'aucun collecteur |
| `quarkus.otel.exporter.otlp.endpoint` | `${OTEL_COLLECTOR_ENDPOINT}` | **aucun hote par defaut** : fourni par l'environnement (`deploy/deployment.yaml`, ConfigMap `souscription-gateway-observability`) |
| `quarkus.otel.traces.sampler` / `.arg` | `parentbased_traceidratio` / `${OTEL_SAMPLE_RATIO:0.1}` | politique d'echantillonnage, 9.2 |
| `quarkus.otel.resource.attributes` | `service.name=souscription-gateway` | nom du service dans le collecteur |
| `quarkus.otel.instrument.rest` | `false` | voir 9.4 : la couche REST attacherait les messages d'exception au span serveur |

### 9.2 Politique d'echantillonnage (sampling policy)

`ErrorAwareSampler` (bean CDI `Sampler`, que Quarkus utilise a la place de l'echantillonneur nomme
par `quarkus.otel.traces.sampler`, dont il reprend la semantique et le ratio) :

| Cas | Decision | `traceparent` propage |
| --- | --- | --- |
| `traceparent` recu, drapeau `01` | echantillonne : la decision de la SPA est suivie | meme trace id, drapeau `01` |
| `traceparent` recu, drapeau `00` | enregistre, non exporte (sauf erreur, 9.3) | meme trace id, drapeau `00` |
| aucun `traceparent` | la passerelle **cree la racine**, echantillonnee au ratio uniforme (`OTEL_SAMPLE_RATIO`, 10 % par defaut) ; sinon enregistree, non exportee (sauf erreur) | nouveau trace id, drapeau selon la decision |

L'identite de trace n'est jamais modifiee d'un saut a l'autre : les clients REST de
`CatalogueClient`, `SimulationClient` et `SubscriptionClient` sont des `@RegisterRestClient` injectes
par CDI, instrumentes automatiquement (span client par appel, `traceparent` = trace id recu + id du
span client). `TracingTest` le verifie sur 3/3 aretes.

### 9.3 Regle « erreur toujours echantillonnee » (error-always rule)

Un echantillonneur de tete decide avant que la reponse existe : avec un ratio uniforme de 10 %, neuf
echecs sur dix ne laisseraient aucune trace. La ou un echantillonneur `parentbased` classique
repondrait `DROP`, `ErrorAwareSampler` repond `RECORD_ONLY` : les spans sont enregistres en memoire
mais ni exportes ni marques echantillonnes. `ErrorTraceRetainer` (bean CDI `SpanProcessor`) conserve
ces spans par trace jusqu'a la fin du span racine local (le span serveur), puis :

- **exporte toute la trace locale** (span serveur, span de parcours, spans clients) si la requete a
  echoue : span racine en statut `ERROR` ou reponse `5xx` ;
- l'abandonne sinon. Les refus `4xx` (y compris les rejets d'arete CSRF, methode, taille) suivent le
  ratio uniforme : une rafale de requetes rejetees ne peut pas forcer l'export de toutes les traces.

Les spans retenus sont transmis au processeur OTLP de `quarkus-opentelemetry`
(`LateBoundSpanProcessor`, sous une vue marquee echantillonnee) et a tout bean CDI `SpanExporter`
(l'exportateur en memoire des tests). Les services aval ayant recu le drapeau `00`, une trace d'erreur
retenue ne contient que les spans de la passerelle. Le tampon est borne : 4096 traces en vol, 128 spans
par trace ; au-dela, une nouvelle trace non echantillonnee n'est pas retenue. Aucun verrou : le
tampon est sans moniteur (`ConcurrentHashMap`, compteurs atomiques), condition de la boucle
d'evenements (`docs/execution-model.md`).

Cout : `RECORD_ONLY` cree et enregistre les spans de 100 % des requetes (en memoire, sans
serialisation ni reseau pour les requetes reussies non echantillonnees).

### 9.4 Attributs autorises (attribute allow-list) et donnees personnelles

Les traces sont lues par un public plus large que les journaux : la regle de 8.4 s'applique. Liste
fermee des cles d'attribut qu'un span de la passerelle peut porter (verifiee par
`TracingTest#noPersonalDataInSpans`) :

| Span | Attributs |
| --- | --- |
| parcours (`catalogue-journey`, `simulation-journey`, `confirm-journey`, `@WithSpan`) | `gateway.journey` (`catalogue`, `simulation`, `confirm` uniquement, `Journeys`), `code.function`, `code.namespace` |
| serveur HTTP | `http.request.method`, `http.response.status_code`, `http.route`, `url.scheme`, `url.path` (**gabarit** de route, `RouteTemplates`), `server.address`, `server.port`, `client.address` (**`redacted`**), `user_agent.original`, `http.request.body.size`, `http.response.body.size`, `network.protocol.version` |
| client REST | `http.request.method`, `http.response.status_code`, `url.full`, `server.address`, `server.port`, `network.peer.address`, `network.peer.port`, `error.type` |
| exception (evenement) | `exception.type`, `exception.message`, `exception.stacktrace`, `exception.escaped` : uniquement pour les exceptions aval et de tolerance aux pannes, dont les messages ne citent que le statut ou la dependance |

Garanties :

- aucun corps, aucun en-tete n'est enregistre : ni jeton CSRF, ni cle de confirmation
  (`Idempotency-Key`), ni nom, prenom, e-mail, date de naissance (noms de champ ou valeurs) ;
- `SpanAttributeHygiene` reduit `url.path` du span serveur au gabarit de route et remplace
  `client.address` (adresse IP de l'utilisateur transmise par l'ingress) par `redacted` ;
- `quarkus.otel.instrument.rest=false` : la couche REST attacherait au span serveur l'exception de
  validation ou de desserialisation, dont le message cite le champ (`putSubscriber.subscriber.firstName`),
  voire la valeur rejetee. Le span serveur Vert.x (avec `http.route`), les spans de parcours et les spans
  clients restent produits ; l'echec reste localise par le statut `ERROR` du span serveur et du span
  client en echec ;
- tout nouvel attribut doit etre ajoute a cette liste et a `TracingTest.ALLOWED_ATTRIBUTES` apres revue.

### 9.5 Correlation journal / trace

Pendant une requete, `mdc.traceId` et `mdc.spanId` de chaque enregistrement JSON sont ceux du span
serveur actif : `TracingTest#logRecordsCarryTheIdsOfTheActiveSpan` capture l'entree de rejet d'une
requete portant un `traceparent` et verifie que son `traceId` est celui de la trace recue et que son
`spanId` est celui d'un span exporte de cette trace.

### 9.6 Levier de retour arriere (rollback lever, CFG-012)

Si le tracage degrade la latence :

1. **sans reconstruction** : `QUARKUS_OTEL_SDK_DISABLED=true` dans l'environnement du deploiement
   (equivalent de `quarkus.otel.sdk.disabled=true`), puis redemarrage progressif des pods ; les
   journaux gardent `traceId`/`spanId` vides ;
2. **au build** : `quarkus.otel.enabled=false` retire l'instrumentation de l'application packagee ;
3. reduction intermediaire : baisser `OTEL_SAMPLE_RATIO` (les traces en erreur restent exportees).

### 9.7 Verification

| Controle | Commande |
| --- | --- |
| identite `traceparent` 3/3 aretes, trace unique de confirmation, racine creee, erreurs a 100 %, 0 donnee personnelle, correlation journal | `mvn -f souscription-gateway/pom.xml clean test -Dtest=TracingTest,LogHygieneTest` |
| trace complete d'un echec, exportee en OTLP par l'application packagee (collecteur local `OtlpCollectorStub`, ratio par defaut, decision appelante `00`) | `mvn -f souscription-gateway/pom.xml verify -Pcucumber -Dcucumber.filter.name="Indisponibilite de l'enregistrement"` |
| aucun hote de collecteur code en dur | `grep -n "otel.exporter.otlp.endpoint" souscription-gateway/src/main/resources/application.properties` |

Note d'execution : Quarkus relit la ligne de commande Maven et refusait l'apostrophe non appariee
du nom de scenario (`unbalanced quotes`). Le profil `cucumber` saute le mojo `generate-code` (sans
source) et ne transmet que `-Pcucumber` comme `MAVEN_CMD_LINE_ARGS` aux JVM de test : la gate se
lance telle quelle avec `-Dcucumber.filter.name="Indisponibilite de l'enregistrement"`.

Non verifiable ici : l'export vers le collecteur reel (dependant de l'environnement), la creation des
spans aval et leur hygiene (applications fournisseurs), la trace de bout en bout incluant la SPA
(`souscription-web`).
