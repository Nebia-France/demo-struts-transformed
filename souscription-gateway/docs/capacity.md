# Capacité et dimensionnement des pools — souscription-gateway (W4-T04)

Plan 19. Objectif de disponibilité retenu en clarification : **99,9 %**.

## Pools sortants

| Client (`configKey`) | Taille de pool | Variable d'environnement | Keep-alive |
| --- | --- | --- | --- |
| `catalogue` | 50 | `CATALOGUE_POOL` | activé |
| `simulation` | 50 | `SIMULATION_POOL` | activé |
| `subscription` | 30 | `SUBSCRIPTION_POOL` | activé |

Connexions entrantes : `quarkus.http.limits.max-connections` = 1000, surchargeable par
`MAX_INBOUND_CONNECTIONS`.

Toutes les valeurs sont surchargeables par environnement — aucun redéploiement d'image n'est
nécessaire pour les ajuster.

## Principes de dimensionnement

1. **Chaque pool est dimensionné face à la capacité déclarée de l'aval**, et non face à la charge
   entrante de la passerelle. La passerelle ne doit pas pouvoir *amplifier* la charge sur une
   dépendance saine : si `souscription-service` annonce une capacité d'écriture inférieure à celle
   des deux services de lecture, son pool est plus petit (30 contre 50). Le pool est le premier
   plafond, pas le dernier.
2. **La protection de l'aval repose sur un bulkhead à la passerelle** (plan 23) : au-delà du pool,
   la demande est rejetée rapidement (503 `problem+json`), **jamais mise dans une file d'attente non
   bornée**. Une file illimitée transforme une saturation aval en épuisement mémoire côté
   passerelle et masque la panne au lieu de la signaler.
3. **Keep-alive et reprise de session TLS sont activés** sur les trois arêtes : chaque arête étant
   en mTLS (`docs/mtls.md`), une poignée de main complète par requête dominerait le budget de
   latence. La réutilisation de connexion amortit ce coût.
4. Les délais (`connect-timeout` 500 ms, `read-timeout` 3 s / 5 s pour `subscription`) bornent la
   durée d'occupation d'une connexion du pool : sans délai, un aval lent sature le pool
   indéfiniment.

## Plafond de capacité mesuré (plan 24 / W5-T04)

Campagne exécutée sur l'image packagée (`target/quarkus-app`, profil non-prod pour dispenser du
matériel mTLS absent du poste), pilote `ab` en keep-alive, 20 clients concurrents, 30 s par
endpoint, trois avals servis par un double local. Script : `src/test/scripts/load-test.sh`.
**Mesuré, pas supposé** — les rapports bruts sont déposés sous `target/load/`.

| Endpoint | Débit soutenu (1 réplique) | p95 | p99 | Réponses non-2xx |
| --- | --- | --- | --- | --- |
| `GET /api/v1/products` | **33 600 req/s** | 1 ms | 2 ms | 7 / 1 000 000 |
| `GET /api/v1/products/{code}` | **40 891 req/s** | 1 ms | 1 ms | 0 / 1 000 000 |
| `GET /api/v1/drafts/{draftId}` | **39 002 req/s** | 1 ms | 1 ms | 0 / 1 000 000 |
| `POST /api/v1/simulations` (fan-out 2 arêtes) | **21 665 req/s** | 1 ms | 2 ms | 0 / 649 958 |

### Surcoût par saut

Moyennes Micrometer du même run (`http_server_requests_seconds` côté bordure,
`http_client_requests_seconds` côté arête) :

| Chemin | Bordure | Saut(s) aval | Surcoût de passerelle |
| --- | --- | --- | --- |
| `/api/v1/products` | 0,370 ms | 0,287 ms | **0,083 ms** |
| `/api/v1/drafts/{draftId}` | 0,300 ms | 0,237 ms | **0,063 ms** |
| `/api/v1/simulations` | 0,840 ms | 0,381 ms (simulation) + 0,280 ms (catalogue), **en parallèle** | **≈ 0,18 ms** |

Le budget de `docs/latency-budget.md` (quelques dizaines de ms par saut en intra-cluster) est tenu
avec deux ordres de grandeur de marge sur un aval local ; en cluster le coût réseau dominera ces
chiffres, qui bornent donc le coût *propre* à la passerelle.

**Appels distants sériés sur le chemin de confirmation : 2** (lecture du brouillon, puis écriture de
confirmation) — propriété verrouillée par `FanOutParityTest`, la lecture produit et la simulation
étant parallélisées.

### Isolation et saturation

| Critère | Valeur mesurée | Verdict |
| --- | --- | --- |
| Appels en attente par arête (`ft_bulkhead_executionsWaiting`) | 0 sur les quatre arêtes après le run | OK |
| Requêtes pendantes au niveau pool (`http_client_pending_requests`) | 0 sur les arêtes `catalogue` et `simulation` pendant que `subscription` est ralentie (`ChaosIT`, test d'isolation) | OK |
| Disjoncteurs ouverts sous charge saine (`ft_circuitbreaker_opened_total`) | 0 | OK |
| Avertissements de blocage de boucle d'évènements (`has been blocked`, NFG-009) | **0** | OK |
| Ratio de disponibilité observé | 7 réponses 5xx sur 3 649 951 requêtes, soit **99,9998 %** | ≥ 99,9 % OK |

Deux jauges sont suivies. **`http_client_pending_requests{clientName=...}`** compte, par arête
aval, les requêtes émises et encore en attente de réponse (niveau pool). Le binder Vert.x de
quarkus-micrometer 3.20.1 construit bien une jauge `http.client.pending` mais **ne l'enregistre
jamais** (constaté dans le bytecode de `VertxHttpClientMetrics` et sur l'interface de management) :
c'est `client/PendingRequests` qui la publie, sous le nom attendu, autour de l'appel du client REST,
en dessous du bulkhead. **`ft_bulkhead_executionsWaiting`** (par méthode de passerelle) compte les
appels mis en file par le bulkhead. Une valeur durablement non nulle signifie que l'arête concernée est le goulot
d'étranglement : il faut alors soit augmenter la variable d'environnement correspondante **après
confirmation de la capacité de l'aval**, soit ajouter une réplique de passerelle — jamais augmenter
le pool unilatéralement, ce qui déplacerait la saturation chez l'aval.

### Absence d'affinité de session (NFG-012)

`src/test/scripts/no-affinity-check.sh` exécuté pour de vrai contre **2 instances** (8080 / 8081)
sans affinité : parcours complet de 10 appels strictement alternés entre les deux répliques (jeton
CSRF émis par l'une et présenté à l'autre, brouillon créé sur l'une et confirmé depuis l'autre) —
**100 % de succès, 0 réponse 4xx/5xx imputable à l'absence d'affinité**, code de sortie 0.

### Re-execution de controle

Campagne rejouee a l'identique sur le meme poste (2 repliques 8098/8099, double aval local,
`DURATION=15s CONCURRENCY=20`) pour verifier la reproductibilite des criteres, et non les chiffres
de plafond (le pilote et le double locaux bornent le debit, cf. enseignement 3) :

| Critere | Resultat de la re-execution |
| --- | --- |
| Reponses en echec sur les quatre phases | **0** |
| `ft_bulkhead_executionsWaiting` sur les six aretes instrumentees | **0** |
| `grep -c "has been blocked" target/application.log` | **0** |
| `no-affinity-check.sh` sur 2 repliques sans affinite | **10 appels, 100 % de succes, 0 4xx/5xx** |

Seconde re-execution (2 repliques 8098/8099, doubles WireMock standalone 3.13.2 servis directement
depuis les fixtures `src/test/resources/wiremock/*`, `ab` keep-alive, `DURATION=10s CONCURRENCY=20`) :

| Critere | Resultat |
| --- | --- |
| `GET /api/v1/products` | 21 375 req/s, p95 2 ms, 10 non-2xx / 213 753 |
| `GET /api/v1/products/{code}` | 32 259 req/s, p95 1 ms, 0 / 322 591 |
| `POST /api/v1/simulations` (fan-out) | 16 374 req/s, p95 2 ms, 0 / 163 745 |
| Ratio de disponibilite observe | 10 echecs / 700 089, soit **99,9986 %** (>= 99,9 %) |
| `grep -c "has been blocked" target/application.log` | **0** |
| `no-affinity-check.sh` sur 2 repliques sans affinite | **100 % de succes, 0 4xx/5xx**, code de sortie 0 |

Les 10 echecs sont tous des `ContextNotActiveException` (500) concentres dans les 3 premieres
secondes de la premiere phase, a froid : c'est le residu deja chiffre en enseignement 1, pas une
regression. Les jauges `ft_*` ont ete lues « absentes » parce que l'interface de management
ecoutait sur 9098 et non sur le port par defaut du script.

Troisieme re-execution (meme protocole : 2 repliques 8098/8099 profil `load`, doubles WireMock
standalone 3.13.2 sur les fixtures `src/test/resources/wiremock/*`, `ab` keep-alive,
`DURATION=10s CONCURRENCY=20`, `MANAGEMENT_URL=http://localhost:9098`) :

| Critere | Resultat |
| --- | --- |
| `GET /api/v1/products` | 22 697 req/s, p95 2 ms, 12 non-2xx / 226 968 |
| `GET /api/v1/products/{code}` | 36 304 req/s, p95 1 ms, 0 / 363 044 |
| `POST /api/v1/simulations` (fan-out) | 16 645 req/s, p95 2 ms, 0 / 166 452 |
| Ratio de disponibilite observe | 12 echecs / 756 464, soit **99,9984 %** (>= 99,9 %) |
| `http_client_pending_requests` (catalogue, simulation) apres la charge | **0** |
| `ft_bulkhead_executionsWaiting` / `ft_circuitbreaker_opened_total` | **0** / **0** |
| `grep -c "has been blocked"` sur les deux journaux | **0** / **0** |
| `no-affinity-check.sh` sur 2 repliques sans affinite | **10 appels, 100 % de succes, 0 4xx/5xx**, code de sortie 0 |

Les 12 echecs sont encore des `ContextNotActiveException` a froid (enseignement 1) ; aucun
disjoncteur ne s'est ouvert.

Quatrieme re-execution (meme protocole : 2 repliques 8098/8099 profil `load`, doubles WireMock
standalone 3.13.2 sur les fixtures `src/test/resources/wiremock/*`, `ab` keep-alive,
`DURATION=10s CONCURRENCY=20`, `MANAGEMENT_URL=http://localhost:9098`) :

| Critere | Resultat |
| --- | --- |
| `GET /api/v1/products` | 23 910 req/s, p95 2 ms, 4 non-2xx / 239 103 |
| `GET /api/v1/products/{code}` | 33 330 req/s, p95 1 ms, 0 / 333 297 |
| `POST /api/v1/simulations` (fan-out) | 15 453 req/s, p95 2 ms, 0 / 154 534 |
| Ratio de disponibilite observe | 4 echecs / 726 934, soit **99,9994 %** (>= 99,9 %) |
| `http_client_pending_requests` (catalogue, simulation) apres la charge | **0** |
| `ft_bulkhead_executionsWaiting` / `ft_circuitbreaker_opened_total` | **0** / **0** |
| `grep -c "has been blocked"` sur les deux journaux | **0** / **0** |
| `no-affinity-check.sh` sur 2 repliques sans affinite | **10 appels, 100 % de succes, 0 4xx/5xx**, code de sortie 0 |

Les 4 echecs sont des `ContextNotActiveException` a froid (enseignement 1) ; aucun disjoncteur ne
s'est ouvert.

Cinquieme re-execution (verification de parite du plan 24, meme protocole : 2 repliques 8098/8099
profil `load`, doubles WireMock standalone 3.13.2 sur les fixtures `src/test/resources/wiremock/*`,
`ab` keep-alive, `DURATION=10s CONCURRENCY=20`, `MANAGEMENT_URL=http://localhost:9098`) :

| Critere | Resultat |
| --- | --- |
| `GET /api/v1/products` | 25 134 req/s, p95 2 ms, 6 non-2xx / 251 337 |
| `GET /api/v1/products/{code}` | 34 789 req/s, p95 1 ms, 0 / 347 889 |
| `POST /api/v1/simulations` (fan-out) | 18 254 req/s, p95 2 ms, 0 / 182 538 |
| Ratio de disponibilite observe | 6 echecs / 781 764, soit **99,9992 %** (>= 99,9 %) |
| `http_client_pending_requests` (catalogue, simulation, subscription) apres la charge | **0** / **0** / **0** |
| `ft_bulkhead_executionsWaiting` / `ft_circuitbreaker_opened_total` | **0** / **0** |
| `grep -c "has been blocked"` sur les deux journaux | **0** / **0** |
| `no-affinity-check.sh` sur 2 repliques sans affinite | **10 appels, 100 % de succes, 0 4xx/5xx**, code de sortie 0 |

Les 6 echecs sont des `ContextNotActiveException` a froid (enseignement 1) ; aucun disjoncteur ne
s'est ouvert.

Un piege reapparu a cette occasion, deja enonce en enseignement 3 et a retenir pour toute future
campagne : un double aval **approximatif** se lit comme un defaut de passerelle. Un champ
excedentaire dans la reponse du double (`description` sur un `ProductSummary`) fait echouer la
deserialisation cote client REST ; le repli du catalogue rend alors 503 et la confirmation 400 —
deux verdicts qui ressemblent a une regression de la passerelle alors que seul le double etait hors
contrat. Les reponses du double doivent etre copiees des fixtures de
`src/test/resources/wiremock/`, pas reecrites de memoire.

### Enseignements de la campagne

1. **Défaut corrigé** : sous charge, un appel sortant repris hors du contexte de requête entrant
   (mise en file par le bulkhead, nouvelle tentative, ou client déjà parti) échouait en
   `ContextNotActiveException` → 500. Ces échecs étant comptés par les disjoncteurs, 12 erreurs sur
   85 000 requêtes suffisaient à **ouvrir le disjoncteur sous une charge pourtant saine** et à faire
   rejeter 95 000 requêtes derrière. `@ActivateRequestContext` sur les trois beans `*Gateway` ramène
   le taux de 24/550 000 à 7/3 650 000 (0,0002 %), et 0 disjoncteur ouvert sous charge saine.
2. **Les abandons client comptent comme des échecs d'arête.** À l'arrêt du pilote, les connexions
   encore actives sont fermées d'un coup et les appels en vol échouent ; les disjoncteurs les
   comptent. D'où la pause entre phases (`PHASE_PAUSE_SECONDS`, 15 s > `delay` du disjoncteur) :
   sans elle, chaque endpoint était mesuré à travers un disjoncteur ouvert par la phase précédente.
3. **Le plafond mesuré est celui de la passerelle, pas celui du double.** Un premier double aval
   (serveur HTTP python multi-thread) saturait à ~9 000 req/s et ses remises à zéro de connexion
   apparaissaient en 503 de passerelle : la mesure mesurait le double. Le pilote et le double
   doivent être tenus au-dessus du système sous test, sans quoi les chiffres ne disent rien.

Les tailles de pool actuelles restent des points de départ cohérents avec les capacités déclarées
des avals : aucune arête n'a été le goulot d'étranglement dans cette campagne, le plafond observé
étant celui du CPU de la bordure.

## Rejeu final sur le build definitif (plan 33 / W7-T04)

Rejeu des suites de charge et de chaos du plan 24 sur le build final, apres les montees de
correctif de la revue CVE (Netty 4.1.140.Final, Vert.x 4.5.36, Jackson 2.18.12, Guava 33.8.0-jre ;
voir `docs/cve-review.md`). L'instrument de mesure est celui de la vague 6 : series Micrometer
`http_server_requests_seconds` et `http_client_requests_seconds`, lues sur `/q/metrics` (port de
management) avant et apres la fenetre.

**Protocole.** Artefact package `target/quarkus-app`, 2 repliques (8098/8099, management 9098/9099)
en profil non-prod `load` (pas de materiel mTLS sur le poste), SDK OpenTelemetry desactive (aucun
collecteur local). Les doubles sont les WireMock standalone 3.13.2 de
`src/test/scripts/dev-stubs.sh --stubs-only`, qui servent les fixtures generees depuis les contrats
(`src/test/resources/wiremock/*`). Pilote `ab` en keep-alive, `CONCURRENCY=20`, `DURATION=30s` par
phase, `PHASE_PAUSE_SECONDS=15`, `DRAFT_ID=11111111-1111-1111-1111-111111111111`. Rapports bruts dans
`target/load/` (artefact CI de l'etape `load`).

### Fenetre de mesure et SLI de disponibilite

| Element | Valeur |
| --- | --- |
| Fenetre soutenue | **2026-11-14T15:25:38Z → 2026-11-14T15:28:39Z** (181 s, 4 phases de 30 s) |
| Requetes applicatives servies (delta `http_server_requests_seconds_count`, hors `/q/*`) | **2 795 779** |
| Reponses 5xx | **8** (toutes `500 ContextNotActiveException`, phase `products-list`, enseignement 1) |
| **SLI passerelle (ratio non-5xx)** | **99,99971 %**, objectif ≥ 99,9 % **tenu** |
| Budget d'erreur consomme sur la fenetre | 0,29 % du budget 0,1 % (8 / 2 796 erreurs admissibles) |

### Plafond de capacite et p95 par endpoint (1 replique)

| Endpoint | Debit soutenu | p50 | **p95** | p99 | Non-2xx |
| --- | --- | --- | --- | --- | --- |
| `GET /api/v1/products` | **29 407 req/s** | 1 ms | **1 ms** | 3 ms | 8 / 882 210 |
| `GET /api/v1/products/{code}` | **29 185 req/s** | 1 ms | **1 ms** | 3 ms | 0 / 875 562 |
| `GET /api/v1/drafts/{draftId}` | **31 140 req/s** | 1 ms | **1 ms** | 3 ms | 0 / 934 189 |
| `POST /api/v1/simulations` (fan-out sur 2 aretes) | **3 458 req/s** | 3 ms | **17 ms** | 58 ms | 0 / 103 737 |

Le plafond de `POST /api/v1/simulations` est celui du **double**, pas de la passerelle
(enseignement 3). Depuis le plan 29, le double simulation verifie les membres requis du contrat
(`matchesJsonPath` sur 4 champs). Interroge seul avec le meme pilote (`ab -k -c 20 -t 15`
directement sur `:18751/v1/simulations`), il plafonne a **5 961 req/s, p95 11 ms**. La passerelle
consomme donc le debit du double, et c'est le CPU du double qui fixe ce chiffre, sans lien avec
la montee Netty/Vert.x. Les trois endpoints a un seul saut, que le double ne limite pas, restent
au niveau des campagnes precedentes, a 29 000 – 31 000 req/s avec un p95 de 1 ms.

### Surcout par saut (moyennes Micrometer du rejeu)

| Chemin | Bordure | Saut aval | Surcout de passerelle |
| --- | --- | --- | --- |
| `/api/v1/products` | 0,538 ms | 0,430 ms | **0,108 ms** |
| `/api/v1/drafts/{draftId}` | 0,502 ms | 0,423 ms | **0,079 ms** |
| `/api/v1/simulations` | 5,279 ms | 2,294 ms (simulation) en parallele de la fiche catalogue | non isolable : la file d'attente du double sature domine |

### Criteres de saturation et de modele d'execution

| Critere | Resultat du rejeu |
| --- | --- |
| `http_client_pending_requests` (catalogue, simulation, subscription) apres la charge | **0** / **0** / **0** |
| `ft_bulkhead_executionsWaiting` (4 methodes instrumentees) | **0** |
| `ft_circuitbreaker_opened_total` sous charge saine | **0** |
| Avertissements `has been blocked` (`grep -c` sur `target/application.log` et `application-b.log`) | **0** / **0** |
| `no-affinity-check.sh` sur 2 repliques sans affinite | **10 appels, 100 % de succes, 0 4xx/5xx**, code de sortie 0 |
| `ChaosIT` (`failsafe:integration-test -Dit.test=ChaosIT`) | **13/13** : 4 modes de panne par aval, cycle du disjoncteur, isolation |

## Rejeu de parite 33 (measurement window, final build)

Rejeu independant de la verification de parite 33, sur le meme build final (commit `9026a942`,
`clean verify -Pcucumber` vert juste avant). Meme protocole que le rejeu W7-T04 ci-dessus : artefact
`target/quarkus-app`, profil non-prod `load`, doubles WireMock generes depuis les contrats
(`dev-stubs.sh --stubs-only`), `load-test.sh` avec `ab`, `CONCURRENCY=20`, `DURATION=30s`,
`PHASE_PAUSE_SECONDS=15`. Les compteurs viennent du delta Micrometer `/q/metrics` (port de
management 9098), releve avant et apres la fenetre.

| Element | Valeur mesuree |
| --- | --- |
| Measurement window | **2026-11-14T15:48:24Z → 2026-11-14T15:51:25Z** (181 s, 4 phases de 30 s, 1 replique) |
| Requetes applicatives servies (hors `/q/*`) | **3 071 166** |
| Reponses 5xx | **2** (`500 ContextNotActiveException`, phase `products-list`, residu a froid de l'enseignement 1) |
| **SLI passerelle (ratio non-5xx)** | **99,99993 %**, objectif ≥ 99,9 % **tenu** |
| `GET /api/v1/products` | capacity 32 786 req/s, p95 **1 ms**, 2 non-2xx / 983 581 |
| `GET /api/v1/products/{code}` | capacity 27 675 req/s, p95 **1 ms**, 0 / 830 249 |
| `GET /api/v1/drafts/{draftId}` | capacity 29 555 req/s, p95 **1 ms**, 0 / 886 656 |
| `POST /api/v1/simulations` (fan-out) | capacity 12 353 req/s, p95 **3 ms**, 0 / 370 599 (plafond fixe par le double) |
| Surcout par saut (moyenne bordure - moyenne saut aval) | `/api/v1/products` 0,465 - 0,381 = **0,084 ms** ; `/api/v1/drafts/{id}` 0,524 - 0,445 = **0,079 ms** |
| `http_client_pending_requests` / `ft_bulkhead_executionsWaiting` / `ft_circuitbreaker_opened_total` | **0** / **0** / **0** |
| `has been blocked` (`target/application.log`, puis `application-a.log` / `application-b.log`) | **0** ; **0** / **0** |
| `no-affinity-check.sh` (2 repliques 8098/8099, meme cle CSRF par execution, jamais versionnee) | **10 appels, 0 4xx/5xx**, code de sortie 0 |
| `ChaosIT` | **13/13**, 0 echec |

Ce rejeu confirme les chiffres W7-T04. Le SLI est mesure sur un poste, avec des doubles d'aval.
Le SLI de production sur une fenetre d'exploitation reelle reste a la charge de l'equipe
d'exploitation (tableaux de bord et alertes de burn-rate du plan 26) ; ce point est flagged for
human review et n'est pas compte comme passe ici.
