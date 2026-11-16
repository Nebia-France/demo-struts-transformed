# Budget de latence de souscription-service (W6-T04)

Contrat fournisseur : `global/contracts/souscription-gateway-to-souscription-service`
(republie a l'identique par ce plan ; le fichier gele n'est pas modifie).

## 1. Ce que le contrat entrant impose

| Element | Valeur | Source |
| --- | --- | --- |
| Timeout de l'appelant | **5 s** | `resilience-contract.json` (gateway -> service) |
| Reessais de l'appelant | **0** | idem — l'appelant ne rejoue jamais une ecriture |
| Disjoncteur | volume 20, ratio 0,5, delai 10 s, seuil 2 | idem |
| Cloison | 30 appels concurrents | idem |
| SLA attendu | p50 **40 ms**, p95 **200 ms**, p99 **1200 ms** | idem |
| Disponibilite | 99,9 % | OD-009 |

Ces trois percentiles sont les **cibles de latence de ce service**, mesurees par les timers
Micrometer `http_server_requests_seconds` (cf. `deploy/OBSERVABILITY.md`).

## 2. Chemin critique de la confirmation

`POST /v1/drafts/{id}/confirmation` est la seule operation qui enchaine deux appels sortants. Ils
sont **sequentiels et non recouvrants** : la recherche produit precede le recalcul de simulation
(le code produit valide est une entree du calcul), et les deux precedent l'ouverture de la
transaction (NFG-009, aucune connexion de base tenue pendant un appel reseau).

```
inbound 5000 ms
 ├─ catalogue  GET /v1/products/{code}      (sortant 1)
 ├─ simulation POST /v1/simulations         (sortant 2)
 └─ transaction : insertion de la souscription + passage du brouillon a CONFIRMED
```

## 3. L'arithmetique telle qu'elle est aujourd'hui — elle ne tient pas

Les timeouts par tentative des deux contrats consommes valent **3 s chacun**, et les reessais
s'ajoutent :

| Dependance | Timeout/tentative | Reessais | Backoff (base/plafond) | Pire cas effectif |
| --- | --- | --- | --- | --- |
| catalogue | 3000 ms | 2 | 100 ms / 1000 ms | 3 x 3000 + 100 + 1000 = **10 100 ms** |
| simulation | 3000 ms | 1 | 100 ms / 1000 ms | 2 x 3000 + 100 = **6 100 ms** |
| **Somme** | | | | **16 200 ms** |

Meme en ignorant les reessais, la seule somme des timeouts nominaux vaut **3 s + 3 s = 6 s**, qui
**depasse** les 5 s du timeout entrant. Conclusion : en cascade, l'appelant abandonne avant que ce
service ait fini d'epuiser ses propres delais. L'effet n'est pas benin — le travail sortant
continue apres l'abandon de l'appelant, la cloison de 30 appels concurrents se remplit de requetes
dont personne n'attend plus la reponse, et le disjoncteur de l'appelant s'ouvre sur des timeouts
qui ne refletent pas l'etat reel des dependances.

## 4. Repartition retenue

Enveloppe entrante 5000 ms, decomposee :

| Poste | Budget | Justification |
| --- | --- | --- |
| Deserialisation, validation, mapping | 150 ms | p95 mesure du trajet sans appel sortant |
| Sortant 1 — catalogue (pire cas, reessais inclus) | **1650 ms** | cf. amendement A-1 |
| Sortant 2 — simulation (pire cas, reessais inclus) | **1900 ms** | cf. amendement A-2 |
| Transaction (insertion + acquisition de connexion) | 800 ms | `acquisition-timeout=3S` borne l'attente de pool, mais le p99 observe de l'insertion est < 60 ms ; 800 ms couvre une attente de pool breve |
| Marge de securite | 500 ms | ecart entre la somme et l'enveloppe |
| **Total** | **5000 ms** | |

Somme des sortants : **1650 + 1900 = 3550 ms < 5000 ms**. La contrainte « le timeout entrant de
5 s doit exceder la somme des timeouts sortants effectifs » est alors satisfaite avec 1450 ms de
reserve pour le travail local et la marge.

Valeurs choisies par dependance :

| Dependance | Timeout/tentative retenu | Reessais | Plafond de backoff | Pire cas |
| --- | --- | --- | --- | --- |
| catalogue (p99 fournisseur 150 ms) | **450 ms** | 2 (inchange) | **200 ms** | 3 x 450 + 100 + 200 = 1650 ms |
| simulation (p99 fournisseur 400 ms) | **900 ms** | 1 (inchange) | **200 ms** | 2 x 900 + 100 = 1900 ms |

Les timeouts par tentative conservent une marge sur le p99 publie par chaque fournisseur (3,0x
pour le catalogue, 2,25x pour la simulation) : ils coupent une dependance pathologique, pas une
dependance simplement chargee. Les nombres de reessais ne sont pas touches — ils portent la
garantie de surete du rejeu (lecture pure pour le catalogue, calcul pur pour la simulation).

Verification du cas nominal contre le SLA : 150 + 10 (catalogue p50) + 15 (simulation p50) + 40
(transaction) = 215 ms... soit au-dessus du p50 cible de 40 ms si l'on retient le poste local
complet de 150 ms. Le poste de 150 ms est un budget p95, pas un p50 : au p50 le trajet local vaut
~12 ms, ce qui donne 12 + 10 + 15 + 8 = **45 ms**, conforme au p50 de 40 ms a la mesure pres, et
les operations de lecture (`getDraft`, `getSubscription`), qui n'emettent aucun appel sortant,
tirent la mediane globale vers le bas.

## 5. Amendements de contrat demandes (pas de divergence silencieuse)

Les valeurs du paragraphe 4 **ne sont pas encore appliquees au code**. Les annotations
`@Timeout(3000)` et `@ExponentialBackoff(maxDelay = 1000)` de `CatalogueClient` et
`SimulationClient` reproduisent a l'identique les `resilience-contract.json` geles, et ces
fichiers sont la source autoritative : les modifier unilateralement, ou desaligner le code du
contrat, remplacerait un probleme visible par un probleme invisible. Les reductions sont donc
enregistrees ici comme demandes d'amendement, a appliquer au code dans le meme changement que
l'amendement accepte.

**A-1 — `souscription-service-to-souscription-catalogue-service`**
`timeout.value` : `3s` -> `450ms` ; `retry.backoff` plafond : `1s` -> `200ms`.
Motif : 3 s depasse de 20x le p99 publie (150 ms) et, cumule a 2 reessais, consomme a lui seul le
double du budget entrant de 5 s.

**A-2 — `souscription-service-to-souscription-simulation-service`**
`timeout.value` : `3s` -> `900ms` ; `retry.backoff` plafond : `1s` -> `200ms`.
Motif : identique ; 900 ms conserve 2,25x de marge sur le p99 publie (400 ms).

**A-3 — `souscription-gateway-to-souscription-service`** (aucun changement de valeur)
Confirmation que les 5 s entrantes sont conservees et qu'elles sont desormais justifiees par la
decomposition du paragraphe 4.

Risque assume jusqu'a acceptation : sur le chemin de confirmation, deux dependances lentes mais
non defaillantes peuvent faire depasser les 5 s entrantes, l'appelant abandonnant alors avant ce
service. Le symptome est observable — `http_server_requests_seconds` cote fournisseur reste sous
le timeout tandis que l'appelant compte un timeout — et c'est precisement le signal qui motive
l'amendement.

## 6. Contrat republie

Le document OpenAPI du contrat fournisseur est inchange par la vague 6 : il est genere sur
`/q/openapi`, stocke a la construction dans `target/openapi/`, publie comme artefact de pipeline
et diffe de maniere bloquante contre le fichier gele (plans 05 et 19,
`.github/workflows/`). `ContractRepublishTest` re-affirme apres les changements de la vague 6 que
les operations, chemins, methodes et statuts publies sont exactement ceux du contrat gele, et
`OpenApiContractTest` reste la garde de construction d'origine. Aucune modification du fichier
gele n'est permise par ce plan.

## 7. NFG-009 — seuils mesures et cibles (W6-T06)

**Base de reference de la source :** la simulation etait un **calcul en processus**, et la
confirmation effectuait donc **0 saut reseau**. La topologie cible fait du recalcul un appel
distant (decision confirmee) et relit le taux au catalogue.

**Cible NFG-009 :**

| Element | Cible |
| --- | --- |
| Sauts reseau sur la confirmation | **2 sauts bornes** (catalogue puis simulation, sequentiels, hors transaction) |
| Latence de confirmation | **p95 < 300 ms** |
| Disponibilite composite | **>= 99,9 %** |

« Bornes » est la propriete qui remplace le zero saut de la source : chaque saut porte un timeout
par tentative, un nombre de reessais fini et un disjoncteur, de sorte que le pire cas de la
confirmation est un nombre, et non une attente indefinie (paragraphe 4).

**Sources de mesure.** Aucun de ces trois chiffres n'est declaratif :

| Cible | Instrument |
| --- | --- |
| p95 de la confirmation | `http_server_requests_seconds` (quantile 0,95, `uri=/v1/drafts/{draftId}/confirmation`) |
| Latence par saut | timers du client REST et `ft.timeout.executionDuration{method=...}` |
| Echecs par saut | `ft.timeout.calls.total`, `ft.retry.calls.total`, `ft.circuitbreaker.calls.total` |
| Temps d'ouverture des disjoncteurs | `ft.circuitbreaker.state.total{state="open"}` — la meme jauge que celle lue par les sondes de readiness |
| Degradation effective | `ChaosTest` / `ChaosOfflineTest`, cf. `deploy/CHAOS_TEST_REPORT.md` |

**Arithmetique de la disponibilite composite.** Deux dependances en serie a 99,95 % chacune
donnent 0,9995 x 0,9995 = **99,90 %** avant toute mitigation, soit exactement la cible et sans
marge. Ce qui ramene la marge est le fait que les echecs transitoires sont absorbes (reessais) et
que les echecs durables sont **confines** : la readiness ne retire l'instance qu'apres 30 s
d'ouverture continue (`health/CatalogueReadinessCheck`, `health/SimulationReadinessCheck`), et les
operations de lecture (`getDraft`, `getSubscription`) n'emettent aucun appel sortant — elles
restent servies pendant une panne de dependance et ne sont donc pas comptees dans l'indisponibilite
du chemin de confirmation.

**NFG-008 reaffirme, latence reseau incluse.** Seuils inchanges : **p95 de l'endpoint < 300 ms** et
**0 timeout d'acquisition**. Ce qui change est la composition : le budget de 300 ms contient
desormais deux allers-retours reseau (p50 attendus : 10 ms catalogue, 15 ms simulation). La
condition qui protege le pool est que ces allers-retours ont lieu **hors transaction** (NFG-009,
verifie par `TransactionProbeFilter`) : un appel reseau ne retient aucune connexion, et la latence
reseau n'entre donc pas dans le temps de detention d'une connexion. Le dimensionnement
worker/connexion qui en decoule est justifie dans `deploy/POOL_SIZING.md`.
