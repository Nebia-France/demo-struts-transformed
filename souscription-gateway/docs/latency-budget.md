# Budget de latence et composition des appels aval (W5-T03)

La source appelait la couche métier **en processus**, à la microseconde : enchaîner les appels était
sans coût. Chaque appel est désormais distant. Les appels **indépendants** sont donc composés en
parallèle (`Uni.combine().all()`), et chaque dépendance est cloisonnée par un `@Bulkhead` afin
qu'un aval lent ne consomme pas toute la capacité de la passerelle (CFG-007, AG-016).

## Un tableau par point d'entrée public

| Endpoint public | Appels aval | Parallèles | Série | Budget de latence |
|---|---|---|---|---|
| `GET /api/v1/products` | 1 (`CatalogueGateway.list`) | — | — | 3000 ms (délai catalogue) |
| `GET /api/v1/products/{code}` | 1 (`CatalogueGateway.detail`) | — | — | 3000 ms |
| `POST /api/v1/simulations` | 2 (`CatalogueGateway.detail` puis `SimulationGateway.compute`) | — | **2 en série** | 6000 ms (3000 + 3000) |
| `POST /api/v1/drafts` | 2 (`SubscriptionGateway.startDraft` **‖** `CatalogueGateway.detail`) | **2** | — | 5000 ms = max(5000, 3000) |
| `GET /api/v1/drafts/{draftId}` | 1 (`SubscriptionGateway.getDraft`) | — | — | 5000 ms |
| `PUT /api/v1/drafts/{draftId}/subscriber` | 1 (`SubscriptionGateway.putSubscriber`) | — | — | 5000 ms |
| `PUT /api/v1/drafts/{draftId}/simulation` | 1 (`SubscriptionGateway.putSimulation`) | — | — | 5000 ms |
| `POST /api/v1/drafts/{draftId}/confirmation` | 1 côté passerelle (`SubscriptionGateway.confirm`) ; l'aval ajoute l'appel **distant** de simulation | — | **2 en série sur le chemin de confirmation** (souscription + simulation) | 5000 ms côté passerelle, couvrant les deux |
| `GET /api/v1/subscriptions/{reference}` | 1 (`SubscriptionGateway.getSubscription`) | — | — | 5000 ms |
| `GET /api/v1/csrf-token` | 0 | — | — | local |

### Règle : au plus **2 appels distants en série** sur le chemin de confirmation

Le chemin de confirmation traverse souscription-service **puis** souscription-simulation-service :
la simulation reste distante (décision de topologie validée), donc sa latence s'ajoute à celle de
l'écriture. C'est un coût **assumé et documenté**, pas un accident. Tout **troisième** appel en
série sur ce chemin doit être justifié ici, dans ce document, avant d'être introduit : sans
justification écrite, il est refusé en revue.

`POST /api/v1/simulations` est la seule autre route à deux appels en série, et ils sont
**réellement dépendants** : le taux annuel autoritatif du produit est lu au catalogue avant d'être
transmis au moteur de projection ; le client ne fournit jamais de taux. La paralléliser serait
incorrect, pas plus rapide.

Inversement, sur `POST /api/v1/drafts` les deux appels n'ont besoin que du code produit : ils sont
composés en parallèle et la route coûte `max(2 appels)` et non leur somme. Le produit rendu dans la
vue est celui du **catalogue**, propriétaire de la donnée produit. Conséquence acceptée : un code
produit inconnu du catalogue est refusé 404 alors que le brouillon a pu être créé en aval ; le
brouillon est un objet serveur de courte durée (il remplace la fenêtre de session de 20 minutes de
la source) et aucune souscription n'en découle.

## Cloisonnement par dépendance

| Dépendance | Méthodes cloisonnées | Concurrence | File d'attente | Au-delà |
|---|---|---|---|---|
| souscription-catalogue-service | `list`, `detail` | 20 | 20 | `BulkheadException` → 503 + `Retry-After` |
| souscription-simulation-service | `compute` | 20 | 20 | idem |
| souscription-service | `startDraft`, `getDraft`, `putSubscriber`, `putSimulation`, `confirm`, `getSubscription` | 20 | 20 | idem |

Chaque valeur est redéclarée dans `application.properties` sous la forme
`<classe>/<méthode>/Bulkhead/value=${VARIABLE:défaut}` : un incident se traite par variable
d'environnement, sans livraison de code. Le cloisonnement est posé **par méthode**, de sorte qu'une
arête saturée ne prive pas les autres de capacité, et la protection de l'aval vient du bulkhead,
**jamais d'une file d'attente non bornée** : au-delà de `value + waitingTaskQueue` l'appel est
refusé immédiatement, ce que `FanOutTest` verrouille.

Les tailles sont cohérentes avec les pools de connexions de [`capacity.md`](capacity.md) : le
bulkhead borne la concurrence *logique* par arête, le pool borne les connexions *physiques*.

## Vérifications

- `FanOutTest` : la latence de `POST /api/v1/drafts` avec deux doubles ralentis est proche de
  `max(délai1, délai2)` et non de leur somme ; et une simulation ralentie et saturée n'empêche pas
  le catalogue de répondre dans son budget.
- `mvn -f souscription-gateway/pom.xml clean test`.
