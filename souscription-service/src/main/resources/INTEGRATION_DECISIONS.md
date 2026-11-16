# Decisions d'integration — souscription-service (W6-T01)

Contrat consomme : `global/contracts/souscription-service-to-souscription-catalogue-service`
(`openapi.yaml`, `resilience-contract.json`, `security-policy.json`).

## 1. Le taux annuel est relu au catalogue a la confirmation

**Decision : oui, le taux est rafraichi au moment de la confirmation.**

Le contrat consomme le dit explicitement : *« Called once per draft validation and once per
confirmation, outside any open transaction »* et *« the confirmation cannot complete unless this
service answers »*. Le catalogue est la seule source autoritaire de `annualRate` ; aucun taux
cache, replique ou par defaut n'est jamais substitue (`fallback` du contrat de resilience).

Consequences :

- le produit copie dans le brouillon a sa creation sert l'affichage du parcours ; la valeur
  **persistee** dans `subscriptions` est celle retournee par le catalogue a la confirmation ;
- le calcul de simulation de la confirmation utilise ce taux rafraichi, et non la copie du
  brouillon ;
- si le produit a ete retire du catalogue entre le debut du parcours et la confirmation (404
  distant), la confirmation repond **409** avec le code `PRODUCT_WITHDRAWN` : le catalogue a
  repondu normalement, ce n'est pas une panne et jamais un 5xx ;
- la lecture d'une souscription deja confirmee (`getSubscription`) n'appelle pas le catalogue :
  les colonnes produit sont denormalisees dans la ligne `subscriptions`.

## 2. Tout appel au catalogue est hors transaction (NFG-009)

`ConfirmationService.prepare(...)` effectue la lecture du catalogue **avant** l'invocation de
`write(...)` / `rereadAfterConflict(...)`, seules methodes `@Transactional`. Aucune connexion de
base n'est retenue pendant un appel reseau. `SubscriptionResource.startDraft` appelle egalement le
catalogue avant toute ecriture de brouillon.

Invariant a preserver : valeurs distantes d'abord, transaction ensuite.

## 3. Statuts et resilience

| Situation distante | Statut rendu par ce service |
| --- | --- |
| 200 | 201 `startDraft` / 200-201 `confirm` |
| 404 (produit inconnu) | 404 `startDraft`, 409 `PRODUCT_WITHDRAWN` a la confirmation |
| autre 4xx, 429 | 503 (aucun reessai : condition non rejouable) |
| 5xx, timeout, disjoncteur ouvert, cloison saturee | 503 |

Parametres appliques, lus dans `resilience-contract.json` et portes par
`integration/CatalogueClient` : timeout 3 s, 2 reessais (backoff exponentiel avec gigue, base
100 ms, plafond 1 s), disjoncteur (volume 20, ratio 0,5, delai 10 s, seuil de retablissement 2),
cloison 20 appels concurrents.

## 4. Repli local

Aucun. Le stub local du catalogue, celui de la simulation et leur indicateur de compatibilite
ont ete retires par `41-souscription-service-final-security-audit` : le client REST est la seule
implementation, et aucun chemin de code ne calcule la simulation localement. Les tests bouchonnent
les fournisseurs par `CatalogueStubServer` et `SimulationStubServer`, derives des contrats geles.
