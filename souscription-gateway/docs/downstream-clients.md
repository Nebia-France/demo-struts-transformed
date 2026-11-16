# Clients REST aval — souscription-gateway (W4-T01)

Plan 17. La source appelait les trois services en intra-processus (`CatalogService`,
`SimulationService`, `SubscriptionService` récupérés depuis un attribut de `ServletContext`) :
`SOURCE_TECHNOLOGY_PROFILE.json` confirme qu'aucun client HTTP sortant n'existait. La passerelle
consomme désormais exactement **trois** contrats, un par arête de la topologie cible.

## Les trois clients

| Client | `configKey` | Contrat consommé (autoritatif) |
| --- | --- | --- |
| `client/CatalogueClient` | `catalogue` | `global/contracts/souscription-gateway-to-souscription-catalogue-service/openapi.yaml` |
| `client/SimulationClient` | `simulation` | `global/contracts/souscription-gateway-to-souscription-simulation-service/openapi.yaml` |
| `client/SubscriptionClient` | `subscription` | `global/contracts/souscription-gateway-to-souscription-service/openapi.yaml` |

Toutes les méthodes renvoient `Uni<...>` : un retour bloquant annulerait le modèle réactif et
provoquerait l'épuisement du pool traité au plan 22. Seule `confirm` renvoie
`Uni<RestResponse<SubscriptionConfirmation>>`, parce que la sémantique idempotente du contrat
(201 à la première confirmation, 200 sur un rejeu de la même `confirmationKey`) ne se lit que dans
le statut ; `DraftResource` la réduit immédiatement à un `ConfirmationOutcome`.

La clé de confirmation fournie par l'appelant est propagée à l'aval dans le corps **et** dans
l'en-tête `Idempotency-Key` déclaré par le contrat (W4-T02) : la passerelle n'en génère aucune, elle
est sans état. Cette arête est la seule écriture et elle ne déclare **aucun `@Retry`** ; voir
`docs/error-mapping.md`.

## Vérification des chemins face aux contrats (un pour un)

Chemins **lus** dans les fichiers `openapi.yaml` ci-dessus, jamais inventés ; le fournisseur reste
propriétaire de sa surface `/v1`.

| Contrat | Opération | Chemin et méthode du contrat | Méthode du client |
| --- | --- | --- | --- |
| catalogue | `listProducts` | `GET /v1/products` | `CatalogueClient.list()` |
| catalogue | `getProduct` | `GET /v1/products/{code}` | `CatalogueClient.detail(code)` |
| simulation | `computeSimulation` | `POST /v1/simulations` | `SimulationClient.compute(request)` |
| subscription | `startDraft` | `POST /v1/drafts` | `SubscriptionClient.startDraft(request)` |
| subscription | `getDraft` | `GET /v1/drafts/{draftId}` | `SubscriptionClient.getDraft(draftId)` |
| subscription | `putDraftSubscriber` | `PUT /v1/drafts/{draftId}/subscriber` | `SubscriptionClient.putSubscriber(...)` |
| subscription | `putDraftSimulation` | `PUT /v1/drafts/{draftId}/simulation` | `SubscriptionClient.putSimulation(...)` |
| subscription | `confirmDraft` | `POST /v1/drafts/{draftId}/confirmation` | `SubscriptionClient.confirm(...)` |
| subscription | `getSubscription` | `GET /v1/subscriptions/{reference}` | `SubscriptionClient.getSubscription(...)` |

Les charges utiles proviennent de `global/contracts/shared-schemas/` : `ProductSummary`,
`ProductDetail`, `SimulationComputeRequest` (qui porte le `annualRate` autoritatif issu du
catalogue), `SimulationResult`, `DraftView`, `SubscriberInput`, `SimulationInput`,
`ConfirmationRequest`, `SubscriptionConfirmation`. Les montants et le taux voyagent en chaînes pour
préserver les échelles `DECIMAL(14,2)` et `DECIMAL(8,6)`.

## Configuration — aucune URI ni délai en Java

`src/main/resources/application.properties` :

```properties
quarkus.rest-client.catalogue.url=${CATALOGUE_BASE_URI}
quarkus.rest-client.catalogue.connect-timeout=500
quarkus.rest-client.catalogue.read-timeout=3000
quarkus.rest-client.simulation.url=${SIMULATION_BASE_URI}
quarkus.rest-client.simulation.connect-timeout=500
quarkus.rest-client.simulation.read-timeout=3000
quarkus.rest-client.subscription.url=${SUBSCRIPTION_BASE_URI}
quarkus.rest-client.subscription.connect-timeout=500
quarkus.rest-client.subscription.read-timeout=5000
```

Le délai de lecture de 5 s sur `subscription` couvre, côté fournisseur, la lecture du produit, la
simulation distante et l'écriture. mTLS et vérification d'hôte sont décrits dans `docs/mtls.md`, le
dimensionnement des pools dans `docs/capacity.md` (plan 19) ; les politiques de tolérance aux pannes
au plan 21.

## Erreurs aval

`client/DownstreamResponseExceptionMapper` traduit le statut aval dans le vocabulaire d'échec de la
passerelle : 404 → `DownstreamNotFoundException`, 409 → `IdempotencyConflictException`, tout le
reste → `DownstreamHttpException` (relais 4xx ou 502). La table gelée reste celle de
`docs/error-mapping.md`.

## Fournisseurs non encore déployés — doublures WireMock (portée test uniquement)

Les trois services frères ne sont pas déployés. `src/test/java/.../client/DownstreamWireMocks.java`
démarre un serveur WireMock par aval et `DownstreamWireMockResource` (enregistré globalement via
`src/test/resources/META-INF/services/io.quarkus.test.common.QuarkusTestResourceLifecycleManager`)
surcharge `quarkus.rest-client.<key>.url` vers le port obtenu.

Les mappings sont **générés à partir des spécifications OpenAPI consommées**
(`openapi.yaml` + `shared-schemas/`) par `src/test/scripts/generate-stubs.sh` (plan 29), et non à
partir des attentes de la passerelle : un stub dérivé des attentes du consommateur rend la suite
verte alors que le vrai fournisseur rejette l'appel. Les valeurs d'exemple vivent dans
`src/test/scripts/stub-fixtures.yaml` et sont validées contre les schémas du contrat ;
`generate-stubs.sh --check` échoue dès qu'un stub dérive. Le plan 32 en vérifie l'identité octet
pour octet.

| Aval | Mappings |
| --- | --- |
| catalogue | `src/test/resources/wiremock/catalogue/mappings/products.json` |
| simulation | `src/test/resources/wiremock/simulation/mappings/simulations.json` |
| subscription | `src/test/resources/wiremock/subscription/mappings/drafts.json` |

Les valeurs des trois produits (`PRUDENT` 0.020000, `EQUILIBRE` 0.040000, `DYNAMIQUE` 0.060000,
dans cet ordre de semence) reprennent celles de `src/main/java/fr/demo/souscription/service/CatalogService.java`.
Le code réservé `@@forced-error@@` déclenche la réponse `InternalError` du contrat, utilisée par
`SecurityHeaderParityTest`.

La dépendance `org.wiremock:wiremock-standalone` est en **portée `test`** : elle n'est pas sur le
classpath de production, et WireMock n'est **jamais actif en profil `prod`**.

## Bascule vers les vrais fournisseurs

La mise en production est un **pur changement d'environnement**, sans modification de code :
renseigner les trois variables

- `CATALOGUE_BASE_URI`
- `SIMULATION_BASE_URI`
- `SUBSCRIPTION_BASE_URI`

avec les URL internes publiées par les contrats (par exemple
`https://souscription-catalogue-service.prod.svc.cluster.local:8443`). Aucune autre action : les
doublures WireMock ne sont pas déployées, et le profil `prod` ne les référence pas.

Pour la suite Cucumber (`-Pcucumber`), `steps/GatewayUnderTest` démarre les mêmes doublures et
passe les trois URL en propriétés système au jar packagé.

## Cas limite `Code produit encadré d'espaces` — step dédié

Gherkin **supprime les espaces encadrant chaque cellule** d'une table `Examples` : la valeur
`|  PRUDENT   |` parvenait au glue sous la forme `PRUDENT`, indiscernable de la ligne nominale, et
la gateway répondait légitimement 200. La correction retenue (plan 17 — vérification de parité) est
celle annoncée : un **step dédié** dans `features/catalogue/catalogue.feature`,
`l'utilisateur demande la fiche du produit de code "PRUDENT" entoure d'espaces`, dont le glue
(`steps/CatalogueSteps.requestProductSheetPaddedWithSpaces`) ajoute le remplissage et l'envoie
percent-encodé (`%20%20PRUDENT%20%20`). Le catalogue aval répond 404, comme la recherche exacte de
l'application source. Aucune assertion n'est affaiblie et les trois exemples nominaux sont intacts ;
les autres cas limites restent couverts : code vide (`/api/v1/products/` → 404), casse erronée,
code mal formaté, et recherche sans code.
