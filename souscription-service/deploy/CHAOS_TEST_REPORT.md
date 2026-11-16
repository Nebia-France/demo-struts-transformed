# CHAOS_TEST_REPORT — degradation du chemin de confirmation (W6-T06)

Objet : prouver que la confirmation degrade correctement quand une dependance distante disparait
ou ralentit. Trois garanties par cas : **503 rejouable**, **liveness `UP`**, **aucune souscription
partielle**.

- Harnais : `src/test/java/fr/demo/souscription/integration/ChaosTest.java`
  (`ChaosTest`, `ChaosOfflineTest`), marques `@Tag("chaos")`.
- Commande : `mvn -f souscription-service/pom.xml clean test`
- Execution de reference : build du 2026-11-09, `BUILD SUCCESS`, 295 tests, 0 echec.

## 1. Valeurs mesurees

Mesures de `ChaosOfflineTest` (les quatre perturbations appliquees aux deux legs reseau du chemin
de confirmation ; durees relevees par le test lui-meme, pas estimees) :

| Cas | Perturbation | Statut rendu | Duree mesuree | Attendu |
| --- | --- | --- | --- | --- |
| 1 | `souscription-simulation-service` arrete (port en refus de connexion) | **503** rejouable | **83 ms** | 503, echec rapide |
| 2 | `souscription-catalogue-service` arrete | **503** rejouable | **396 ms** | 503, 1 appel + 2 reessais |
| 3 | latence injectee **2500 ms** (< timeout 3000 ms) | **200**, valeur du fournisseur | **2567 ms** | succes, sans reessai |
| 4 | latence injectee **3500 ms** (> timeout 3000 ms) | **503** rejouable | **3007 ms** | 503 au timeout |

Lectures :

- **Cas 1 — 83 ms.** Un refus de connexion est detecte immediatement ; le timeout de 3 s ne sert
  qu'aux fournisseurs lents, pas aux fournisseurs absents. L'appelant obtient son 503 sans attendre.
- **Cas 2 — 396 ms.** Trois tentatives (`maxRetries = 2`) plus le backoff exponentiel avec gigue
  (base 100 ms, plafond 1 s), soit la signature attendue du contrat de resilience du catalogue.
- **Cas 3 — 2567 ms.** La latence a bien ete subie (> 2500 ms) et l'appel a reussi : la valeur
  rendue est celle du fournisseur, verifiee contre la projection attendue — **aucun repli, aucune
  valeur portee par le brouillon**. 2567 < 3000 : une seule tentative, le timeout n'a pas tranche.
- **Cas 4 — 3007 ms.** Le timeout tranche a 3000 ms et **sans reessai** : `TimeoutException`
  n'est pas dans le `retryOn` du client, un fournisseur qui depasse son delai n'est donc pas
  re-sollicite. Le pire cas de ce leg est borne a ~3 s et non a 6,1 s.

## 2. Garanties verifiees, par cas

| Garantie | Verifiee par | Cas 1 | Cas 2 | Cas 3 | Cas 4 |
| --- | --- | --- | --- | --- | --- |
| 503 rejouable (type `problem+json` distinct par cause) | `ChaosTest`, `ChaosOfflineTest` | oui | oui | s.o. (201) | oui |
| Liveness `UP` (`GET /q/health/live`) | les deux classes | oui | oui | oui | oui |
| Nombre de lignes `subscriptions.subscriptions` inchange | `ChaosTest` | oui | oui | +1 (attendu) | oui |

Codes rendus a la SPA :

- recalcul indisponible → `type=urn:horizon:simulation-unavailable`, code de champ
  `SIMULATION_UNAVAILABLE_RETRYABLE` ;
- catalogue indisponible → `type=urn:horizon:service-unavailable`.

Les deux sont **rejouables a l'identique** : aucune confirmation n'a ete emise, la saisie reste
dans le brouillon.

## 3. Pourquoi la liveness reste `UP`

Une dependance en panne n'est pas un processus malade. Un redemarrage ne reparerait rien et
detruirait les requetes en vol. La liveness ne depend donc d'aucune dependance sortante. Le retrait
de rotation est la decision de la **readiness**, et seulement sur une panne **durable** :
`health/CatalogueReadinessCheck` et `health/SimulationReadinessCheck` ne passent `DOWN` qu'apres
**30 s d'ouverture continue** du disjoncteur correspondant (`CircuitBreakerOpenWindow.DURABLY_OPEN`,
etat lu dans la jauge `ft.circuitbreaker.state.total{state="open"}`). Aucun des quatre cas
ci-dessus ne franchit ce seuil : ce sont des fautes transitoires, rendues a l'appelant en 503 sans
retirer l'instance.

## 4. Couverture des deux classes

| Classe | Exige | Porte |
| --- | --- | --- |
| `ChaosTest` | base reelle (Dev Services PostgreSQL, donc un daemon de conteneurs) | les quatre cas de bout en bout via `POST /v1/drafts/{id}/confirmation`, **avec** le comptage de lignes |
| `ChaosOfflineTest` | rien | les quatre memes perturbations sur les legs reseau, avec les durees mesurees ci-dessus |

Sans daemon de conteneurs, `ChaosTest` est ignore (`@RequiresContainerRuntime`,
convention du module) et `ChaosOfflineTest` fournit neanmoins les quatre mesures. L'environnement
de l'execution de reference n'avait pas de daemon de conteneurs : les durees du paragraphe 1
proviennent de `ChaosOfflineTest`, et les comptages de lignes du paragraphe 2 sont assertes par
`ChaosTest` en CI Docker.

## 5. Consequences retenues

1. Le pire cas du leg simulation est **~3 s** et non 6,1 s : le timeout n'est pas reessaye. Le
   paragraphe 3 de `deploy/LATENCY_BUDGET.md` surestime donc ce poste, ce qui va dans le sens de la
   surete et ne change pas la conclusion (la somme des timeouts nominaux, 6 s, depasse toujours
   l'enveloppe entrante de 5 s — les amendements A-1 et A-2 restent demandes).
2. Un fournisseur **absent** coute 83 ms, un fournisseur **lent** coute jusqu'a 3 s. C'est le
   second cas qui met le budget entrant en danger, et c'est lui que les amendements A-1 / A-2
   ciblent.
3. Aucune ligne partielle n'a ete observee dans aucun cas : les appels sortants precedent
   l'ouverture de la transaction (NFG-009), donc un echec reseau ne peut pas laisser une
   souscription a moitie ecrite.

## 6. Rejeu avec la configuration finale (W8-T05, plan 40)

- Harnais : `deploy/bench/Benchmark.java` (methode `chaos`), artefact package
  `target/quarkus-app/quarkus-run.jar` sur PostgreSQL 16.15 reel, bouchons HTTP des deux contrats
  consommes ; les lignes `subscriptions.subscriptions` sont comptees en base (le comptage n'est
  donc plus seulement asserte en CI Docker).
- Commande : `souscription-service/deploy/bench/run-benchmark.sh` ; resultats bruts
  `deploy/bench/results-2026-11-13.json`, champ `chaos_replay`.
- Execution : 2026-11-13, apres les phases de charge et le redemarrage progressif de
  `deploy/BENCHMARK_REPORT.md`.

| Cas | Perturbation | Statut rendu | Type `problem+json` | Duree mesuree | Liveness | Delta lignes | Rejeu apres retablissement |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | simulation arretee | **503** | `urn:horizon:simulation-unavailable` | **53 ms** | 200 | **0** | 201, +1 ligne |
| 2 | catalogue arrete | **503** | `urn:horizon:service-unavailable` | **376 ms** | 200 | **0** | 201, +1 ligne |
| 3 | latence 2500 ms | **201** | — | **2508 ms** | 200 | +1 (attendu) | s.o. |
| 4 | latence 3500 ms | **503** | `urn:horizon:simulation-unavailable` | **3008 ms** | 200 | **0** | 201, +1 ligne |

Confirmations :

- le **503 rejouable** tient : les trois cas en echec rendent 503 avec un type distinct par cause,
  et le rejeu a l'identique apres retablissement rend 201 ;
- **zero souscription partielle** : delta de lignes 0 dans les trois cas en echec, +1 exactement au
  rejeu et au cas 3 ;
- les durees restent dans la signature du paragraphe 1 (echec rapide, 3 tentatives du catalogue,
  timeout simulation a ~3 s sans reessai).

### 6.1 Rejeu de parite (plan 40, configuration finale)

- Commande : `SKIP_BUILD=1 souscription-service/deploy/bench/run-benchmark.sh` (deux executions
  completes, P1 et P2), resultats bruts `deploy/bench/results-2026-11-13-parity-run1.json` et
  `deploy/bench/results-2026-11-13-parity.json`, champ `chaos_replay`. Configuration : celle de la
  fin des vagues 7-8 (filtres de securite, timeouts, bouchons locaux retires).

| Cas | Perturbation | Statut P1 / P2 | Type `problem+json` | Duree P1 / P2 | Liveness | Delta lignes | Rejeu apres retablissement |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | simulation arretee | **503** / **503** | `urn:horizon:simulation-unavailable` | **51 / 114 ms** | 200 | **0** | 201, +1 ligne |
| 2 | catalogue arrete | **503** / **503** | `urn:horizon:service-unavailable` | **267 / 261 ms** | 200 | **0** | 201, +1 ligne |
| 3 | latence 2500 ms | **201** / **201** | — | **2504 / 2515 ms** | 200 | +1 (attendu) | s.o. |
| 4 | latence 3500 ms | **503** / **503** | `urn:horizon:simulation-unavailable` | **3013 / 3009 ms** | 200 | **0** | 201, +1 ligne |

Le 503 rejouable et l'absence de souscription partielle (count = 0 dans les cas en echec) tiennent
dans la configuration finale.

### 6.2 Rejeu conteneurise (plan 40, image Jib finale)

- Commande : `BENCH_RUNTIME=docker SKIP_BUILD=1 souscription-service/deploy/bench/run-benchmark.sh`
  apres `mvn -f souscription-service/pom.xml package -DskipTests -Dquarkus.container-image.build=true`.
  Chaque replique est un conteneur de l'image `horizon/souscription-service:1.0.0-SNAPSHOT` sous
  `--memory 512m` (limite de `deploy/deployment.yaml`). Resultats bruts
  `deploy/bench/results-2026-11-13-parity-docker.json` (P3), champ `chaos_replay`.

| Cas | Perturbation | Statut P3 | Type `problem+json` | Duree P3 | Liveness | Delta lignes | Rejeu apres retablissement |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | simulation arretee | **503** | `urn:horizon:simulation-unavailable` | **185 ms** | 200 | **0** | 201, +1 ligne |
| 2 | catalogue arrete | **503** | `urn:horizon:service-unavailable` | **318 ms** | 200 | **0** | 201, +1 ligne |
| 3 | latence 2500 ms | **201** | — | **2515 ms** | 200 | +1 (attendu) | s.o. |
| 4 | latence 3500 ms | **503** | `urn:horizon:simulation-unavailable` | **3014 ms** | 200 | **0** | 201, +1 ligne |

Le 503 rejouable et zero souscription partielle (count = 0 dans les trois cas en echec) tiennent
aussi dans l'image conteneurisee finale. Le cas 1 est plus lent qu'en processus (185 ms contre
51-114 ms) parce que le refus de connexion traverse le transfert de ports de la VM colima.
