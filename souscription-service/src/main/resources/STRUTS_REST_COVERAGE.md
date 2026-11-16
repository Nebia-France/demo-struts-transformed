# Couverture Struts → REST (souscription-service)

Table de correspondance produite **avant** toute écriture de ressource REST (mitigation R-001).
Sources vérifiées :

- `src/main/webapp/WEB-INF/struts-config.xml` (action-mappings `SubscriptionAction`)
- `src/main/java/fr/demo/souscription/action/SubscriptionAction.java` (dispatch par chemin Struts)
- `global/contracts/souscription-gateway-to-souscription-service/openapi.yaml` (contrat gelé, 6 opérations)

Versionnement confirmé : `/api/v1` public au niveau de `souscription-gateway`, `/v1` interne au service.

## 1. Chemins Struts → opérations REST

| Struts path | Method | Target operation | Notes |
| --- | --- | --- | --- |
| `/commencer` | POST | `POST /v1/drafts` (`startDraft`) | **changement de statut accepté** : un code produit inconnu répondait **400** dans la source (erreur HTTP 400 de la source, BASELINE.md §4.1) et répond **404** dans la cible, conformément au contrat gelé (400 réservé au corps invalide, 404 pour produit inconnu au catalogue). Changement délibéré, à asserter tel quel par le test e2e du plan 37. |
| `/commencer` | GET | — (`405` natif JAX-RS) | la source répond une erreur HTTP 405 ; aucune opération GET déclarée sur `/v1/drafts`, le 405 est celui natif de la méthode non déclarée (pas de garde écrite à la main) |
| `/souscripteur` | GET | `GET /v1/drafts/{draftId}` (`getDraft`) | ré-affichage et pré-remplissage du formulaire assurés par le SPA |
| `/souscripteur` | POST | `PUT /v1/drafts/{draftId}/subscriber` (`putDraftSubscriber`) | validation souscripteur autoritaire ; régénère le `confirmationKey` |
| `/simulation` | GET | `GET /v1/drafts/{draftId}` (`getDraft`) | idem : état du brouillon rendu par le SPA |
| `/simulation` | POST | `PUT /v1/drafts/{draftId}/simulation` (`putDraftSimulation`) | `command=calculer\|continuer` devient une navigation SPA ; régénère le `confirmationKey` |
| `/recapitulatif` | GET | `GET /v1/drafts/{draftId}` (`getDraft`) | état complet du brouillon pour le récapitulatif |
| `/recapitulatif` | POST | — (`405` natif JAX-RS) | la source répond une erreur HTTP 405 ; aucune méthode POST déclarée sur `/v1/drafts/{draftId}`, 405 natif (pas de garde manuelle, afin de conserver les mappers `application/problem+json` du plan 22) |
| `/confirmer` | POST | `POST /v1/drafts/{draftId}/confirmation` (`confirmDraft`) | idempotent sur `confirmationKey` (201 puis 200 au rejeu) ; clé divergente → 409 ; `accepted=false` → 400 ; `SQLException` → 503 |
| `/confirmer` | GET | — (`405` natif JAX-RS) | la source répond une erreur HTTP 405 ; aucune méthode GET déclarée sur `/v1/drafts/{draftId}/confirmation`, 405 natif |
| `/confirmation` | GET | `GET /v1/subscriptions/{reference}` (`getSubscription`) | lit la souscription **achevée**, pas le brouillon |
| `/confirmation` | POST | — (`405` natif JAX-RS) | la source répond une erreur HTTP 405 ; aucune méthode POST déclarée sur `/v1/subscriptions/{reference}`, 405 natif |
| défaut (erreur HTTP 404 de la source) | — | 404 natif JAX-RS | aucun handler spécifique |
| `/catalogue` | GET | — (`CatalogAction`, hors de ce service) | n'appartient pas à `SubscriptionAction` : forward JSP `catalogue.jsp`. Données servies par `souscription-catalogue-service`, vue rendue par `souscription-web` (voir `OUT_OF_SCOPE_VIEWS.md`) |
| `/produit` | GET | — (`CatalogAction`, hors de ce service) | forward JSP `produit.jsp` : détail produit servi par `souscription-catalogue-service`, vue rendue par `souscription-web` |

Branches de la source sans opération propre, couvertes par les lignes ci-dessus :

- La branche de reprise de la source, qui renvoyait vers `/catalogue.do?notice=parcours` lorsque
  l'état d'étape n'avait pas de brouillon :
  devient un `404` sur le `draftId` (voir §2) et une navigation SPA vers le catalogue.
- Rejeu de `/confirmer` sans brouillon mais avec `completed` + `confirmationKey` correspondants :
  couvert par l'idempotence de `confirmDraft` (200).
- Redirections de garde (`draft.getSubscriber() == null` → `/souscripteur.do`,
  `draft.getSimulation() == null` → `/simulation.do`) : devient `409` sur `confirmDraft`
  (brouillon incomplet) et une navigation SPA pour les étapes.

## 2. Statuts déclarés par le contrat gelé

| Operation | Statuts |
| --- | --- |
| `startDraft` | 201, 400, 404, 500, 503 |
| `getDraft` | 200, 404, 500 |
| `putDraftSubscriber` | 200, 400, 404, 409, 500 |
| `putDraftSimulation` | 200, 400, 404, 409, 500, 503 |
| `confirmDraft` | 201, 200, 400, 404, 409, 500, 503 |
| `getSubscription` | 200, 404, 500 |

Le contrat gelé ne déclare **aucun 410 Gone**. Un brouillon expiré (purge de rétention) ou inconnu
est un **404**. Toute mention de 410 dans une prose de plan de vague est supersédée par ce contrat.

## 3. Réalisé par le SPA

Propriétaire : **`souscription-web`** (application Angular). Comportements de la source qui n'ont
aucune opération dans ce service :

- Les redirections inter-étapes (vers `/souscripteur.do`, `/simulation.do`,
  `/recapitulatif.do`, `/confirmation.do`, et la reprise vers
  `/catalogue.do?notice=parcours`) → routeur Angular de **`souscription-web`**.
- Le paramètre de requête `command=calculer|continuer` → navigation de **`souscription-web`**
  uniquement (`calculer` reste sur l'étape simulation, `continuer` avance au récapitulatif).
  Le scénario Gherkin `Commande inconnue a l'etape de simulation` est donc alloué à
  **`souscription-web`** (plan 39).
- Le rendu des dates au format local jour/mois/année et des décimales à virgule
  (`populateSimulation`, `getBirthDateLabel`) → formatage dans **`souscription-web`** ; le service
  échange en ISO-8601 et en décimal à point.
- Les attributs de présentation `pageTitle` et `step` (fil d'étapes 1..5) et les forwards JSP
  (`souscripteur`, `simulation`, `recapitulatif`, `confirmation`) → vues de **`souscription-web`**.
- Le rendu des `errors` de formulaire → le service renvoie `application/problem+json`
  (`FieldErrors`), l'affichage par champ est assuré par **`souscription-web`**.

## 4. Valeurs autoritatives du serveur

Le capital projete et le total verse (`simulation.finalCapital`, `simulation.contributed`) sont
calcules par le serveur et sont autoritatifs : l'appelant ne doit ni les substituer, ni les
recalculer, ni se rabattre sur une valeur fournie par le client. La regle complete, ses motifs et le
proprietaire de sa verification figurent dans `souscription-service/API_VERSIONING.md`, section
« Valeurs autoritatives du serveur (obligation du consommateur) ».
