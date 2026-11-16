# Matrice de routage `*.do` → `/api/v1` — souscription-gateway (W0-T11)

Source : `src/main/webapp/WEB-INF/struts-config.xml` (8 `<action>`, 1 `<form-bean>`,
6 `<global-forward>`). Cette matrice est l'entrée directe du plan 07 (implémentation des
ressources REST de la gateway).

Convention de versionnage retenue : **`/api/v1` en public (gateway)**, `/v1` en interne
(services amont, joignables uniquement dans le cluster, mTLS).

## Actions (8 lignes)

| Chemin source | Classe Action source | Cible `/api/v1` + méthode HTTP | Classe ressource cible | Service amont appelé |
| --- | --- | --- | --- | --- |
| `/catalogue.do` | `CatalogAction` | `GET /api/v1/products` | `CatalogueResource` | catalogue |
| `/produit.do?code=` | `CatalogAction` | `GET /api/v1/products/{code}` | `CatalogueResource` | catalogue |
| `/commencer.do` | `SubscriptionAction` | `POST /api/v1/drafts` | `DraftResource` | subscription |
| `/souscripteur.do` | `SubscriptionAction` | `PUT /api/v1/drafts/{draftId}/subscriber` | `DraftResource` | subscription |
| `/simulation.do` | `SubscriptionAction` | `POST /api/v1/simulations` (calcul) et `PUT /api/v1/drafts/{draftId}/simulation` (rattachement) | `SimulationResource`, `DraftResource` | simulation (+ catalogue pour le taux), subscription |
| `/recapitulatif.do` | `SubscriptionAction` | `GET /api/v1/drafts/{draftId}` | `DraftResource` | subscription |
| `/confirmer.do` | `SubscriptionAction` | `POST /api/v1/drafts/{draftId}/confirmation` | `DraftResource` | subscription |
| `/confirmation.do` | `SubscriptionAction` | `GET /api/v1/subscriptions/{reference}` | `ConfirmationResource` | subscription |

## Contrôle un-à-un avec le contrat public (plan 07)

`global/contracts/souscription-web-to-souscription-gateway/openapi.yaml` est la liste autoritaire
des opérations. Les 9 opérations du contrat se répartissent ainsi dans
`src/main/java/fr/demo/souscription/gateway/api/` :

| Opération du contrat | `operationId` | Implémentation |
| --- | --- | --- |
| `GET /products` | `listProducts` | `CatalogueResource.list()` |
| `GET /products/{code}` | `getProduct` | `CatalogueResource.detail(String)` |
| `POST /simulations` | `simulate` | `SimulationResource.simulate(SimulationRequest)` |
| `POST /drafts` | `startDraft` | `DraftResource.start(DraftStartRequest)` |
| `GET /drafts/{draftId}` | `getDraft` | `DraftResource.get(String)` |
| `PUT /drafts/{draftId}/subscriber` | `putDraftSubscriber` | `DraftResource.putSubscriber(...)` |
| `PUT /drafts/{draftId}/simulation` | `putDraftSimulation` | `DraftResource.putSimulation(...)` |
| `POST /drafts/{draftId}/confirmation` | `confirmDraft` | `DraftResource.confirm(...)` |
| `GET /subscriptions/{reference}` | `getSubscription` | `ConfirmationResource.get(String)` |
| `GET /csrf-token` | `issueCsrfToken` | `CsrfTokenResource.issue(...)` |

Deux écarts par rapport à la première rédaction de cette matrice, tranchés en faveur du contrat :

1. Le calcul de simulation est exposé en `POST /api/v1/simulations` (opération sans effet de bord,
   le taux annuel étant résolu par la gateway auprès du catalogue), et le rattachement au brouillon
   en `PUT /api/v1/drafts/{draftId}/simulation`. La ligne `/simulation.do` couvre donc deux
   opérations cibles, ce qui porte le contrat à 9 opérations pour 8 actions source.
2. La confirmation est `POST /api/v1/drafts/{draftId}/confirmation` et non `POST
   /api/v1/subscriptions`. Elle est portée par `DraftResource` : Jakarta REST sélectionne la classe
   racine la mieux adaptée à l'URI **avant** de chercher la méthode, donc une seconde classe racine
   montée sur `/api/v1` serait masquée par `/api/v1/drafts` et renverrait 404.
   `ConfirmationResource` conserve la lecture `GET /api/v1/subscriptions/{reference}`.

Les clients amont (`CatalogueClient`, `SimulationClient`, `SubscriptionClient`) sont déclarés dans
`.../gateway/client/` avec une implémentation de substitution `@ApplicationScoped` sous
`.../gateway/client/stub/`, remplacée par `@RegisterRestClient` au plan 17.

Les 8 chemins source se répartissent donc sur **4 classes de ressources** cibles
(`CatalogueResource`, `DraftResource`, `SimulationResource`, `ConfirmationResource`) et
**3 services amont** (`catalogue`, `subscription`, `simulation`), conformément au résumé
`GatewayConfig.summaryOfDownstreams()` journalisé au démarrage. Les URL de base de ces trois
services seront ajoutées à la configuration lors du plan 07 (clients REST typés).

Note de topologie : `POST /api/v1/drafts/{id}/simulation` et la simulation appelée à la
confirmation restent des appels **distants** vers `souscription-simulation-service` (décision
validée) ; le brouillon (`drafts`) est **persisté** en table dédiée du schéma `subscriptions`
côté `souscription-service`, et remplace l'état de session HTTP de 20 minutes du source.

## Idiome de dispatch abandonné — propriétaires du séquencement des étapes

Le dispatch par paramètre `parameter` de `SubscriptionAction` (commandes `commencer`,
`souscripteur`, `calculer`, `recapitulatif`, `confirmer`) **n'est pas porté** dans la gateway :
celle-ci n'expose que des routes REST sans état et ne connaît pas l'ordre des étapes.

| Comportement source abandonné | Propriétaire cible | Justification |
| --- | --- | --- |
| Séquencement des étapes du parcours (step/etape ordering, navigation d'une étape à la suivante) | `souscription-web` (routeur Angular) | la navigation devient cliente ; la gateway ne rend aucune vue et ne fait aucun forward |
| Validité d'une transition d'étape sur un brouillon (ex. simuler avant d'avoir saisi le souscripteur) | `souscription-service` (propriétaire du brouillon persisté) | l'état d'avancement est une donnée du brouillon, pas de la gateway |
| Cas « commande inconnue » (`Commande inconnue a l'etape de simulation`) | `souscription-web` pour le choix de route, `souscription-service` pour le refus métier | il n'existe plus de paramètre `command` : une commande inconnue n'a plus de représentation dans la gateway |

Conformément à la matrice du plan 30, les scénarios Gherkin
« Chemin inconnu dans le parcours » et « Commande inconnue a l'etape de simulation » restent
néanmoins **à la charge de la gateway** : un chemin inconnu sous `/api/v1` doit répondre **404**
(comportement préservé du source, qui renvoyait 404 sur `/console` et sur un chemin JSP direct) et
une méthode HTTP non conforme à la matrice ci-dessus doit répondre **405**. Ces deux réponses sont
vérifiées par `RouteSurfaceParityTest`.

## `<form-bean>`

`subscriptionForm` (`fr.demo.souscription.form.SubscriptionForm`) n'a pas d'équivalent dans la
gateway : il est remplacé par les DTO JSON de requête/réponse des ressources ci-dessus, validés
par Hibernate Validator.

## `<global-forward>` — 6 cibles JSP abandonnées

| `<forward name>` | Chemin JSP source | Devenir |
| --- | --- | --- |
| `catalogue` | `/WEB-INF/jsp/catalogue.jsp` | **abandonné** → route Angular dans `souscription-web` |
| `produit` | `/WEB-INF/jsp/produit.jsp` | **abandonné** → route Angular dans `souscription-web` |
| `souscripteur` | `/WEB-INF/jsp/souscripteur.jsp` | **abandonné** → route Angular dans `souscription-web` |
| `simulation` | `/WEB-INF/jsp/simulation.jsp` | **abandonné** → route Angular dans `souscription-web` |
| `recapitulatif` | `/WEB-INF/jsp/recapitulatif.jsp` | **abandonné** → route Angular dans `souscription-web` |
| `confirmation` | `/WEB-INF/jsp/confirmation.jsp` | **abandonné** → route Angular dans `souscription-web` |

La navigation serveur (forward JSP) disparaît : elle devient de la navigation côté client
(routeur Angular). La gateway ne rend aucune vue et ne fait aucun forward.

## Aucun alias de compatibilité `*.do`

Les chemins `*.do` **ne sont pas un contrat préservé** : ils étaient consommés uniquement par les
JSP du même WAR, jamais par un client externe. Aucun alias, redirection ou filtre de
compatibilité `*.do` n'est créé dans la gateway.

Preuve : `grep -rnE '/[A-Za-z_-]+\.do\b' souscription-gateway/src` → aucun résultat.

> Le grep littéral `grep -rn "\.do" souscription-gateway/src` ne renvoie aucune occurrence :
> la méthode de configuration anciennement nommée `downstreamSummary()` (dont l'appel
> `.downstreamSummary()` contenait la sous-chaîne `.do`) a été renommée
> `summaryOfDownstreams()`, méthode interne au module utilisée uniquement par
> `GatewayStartup`.
