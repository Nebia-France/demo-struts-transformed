# Normalisation des erreurs — RFC 7807 (W2-T03)

Plan 12. Remplace les sept déclarations `<error-page>` du descripteur de déploiement source
(400, 403, 404, 405, 409, 500, `java.lang.Throwable`), qui renvoyaient toutes vers une seule vue
HTML rendue par le conteneur. Les libellés destinés à l'utilisateur ne sont **pas** portés : ils
appartiennent à `souscription-web`, qui les rend à partir de `status` et de `errors`.

Toute réponse de statut ≥ 400 est un document `application/problem+json` conforme au schéma
`Problem` publié sur `/q/openapi` (NFG-018).

## Représentation

| Membre     | Type                        | Remarque                                                        |
|------------|-----------------------------|-----------------------------------------------------------------|
| `type`     | URI                         | `https://example.com/horizon-souscription/problems/{slug}`      |
| `title`    | chaîne                      | Libellé court et stable du type de problème                     |
| `status`   | entier                      | Identique au statut HTTP de la réponse                          |
| `detail`   | chaîne                      | Jamais de trace ni de détail interne                            |
| `instance` | chaîne                      | Identifiant de requête (`X-Request-Id` si fourni, sinon généré) |
| `errors`   | tableau `[{field, message, code}]` | Présent uniquement sur les échecs de validation (400, 422) ; forme de `shared-schemas/FieldErrors.json` |

La corrélation passe exclusivement par `instance` ; il est exploité par la traçabilité du plan 28.

## Table des statuts (gelée — consommée par les plans 20 et 21)

### Échecs présents dans la source

| Déclencheur                                                    | Statut | `type` slug              |
|----------------------------------------------------------------|--------|--------------------------|
| `ConstraintViolationException` (validation), `BadRequestException` | 400 | `bad-request`            |
| Rejet CSRF (`ForbiddenException`, plan 16)                      | 403    | `forbidden`              |
| `NotFoundException`, `DownstreamNotFoundException`, route inconnue | 404  | `not-found`              |
| `NotAllowedException` (liste blanche de méthodes, plan 13)      | 405    | `method-not-allowed`     |
| `IdempotencyConflictException`                                  | 409    | `conflict`               |
| Toute autre exception (dernier recours, `Throwable`)            | 500    | `internal-error`         |

### Classes d'échec nouvelles, introduites par la découpe en services

| Déclencheur                                                | Statut | `type` slug              | En-tête        |
|------------------------------------------------------------|--------|--------------------------|----------------|
| Aval 4xx relayable (400, 404, 409, 422)                     | identique | selon le statut relayé | —              |
| Aval 4xx non relayable, ou aval 5xx autre que 503           | 502    | `downstream-error`       | —              |
| Aval 503 (indisponibilité annoncée)                         | 503    | `service-unavailable`    | `Retry-After: 30` |
| Expiration de délai (lecture ou connexion)                  | 504    | `downstream-timeout`     | —              |
| Circuit ouvert (`CircuitBreakerOpenException`)              | 503    | `service-unavailable`    | `Retry-After: 30` |

Table finale du plan 20 (W4-T05), conforme au comportement implémenté. Une seule précision par
rapport à la table gelée du plan 12 : un **503 aval est relayé tel quel** au lieu de devenir un 502.
Un 503 est par définition le signal « indisponibilité transitoire » ; le transformer en 502
annoncerait un échec définitif à l'appelant, et les neuf opérations adossées à un aval déclarent
déjà 503 avec `Retry-After` dans le contrat public. Tout autre 5xx reste un 502.

Le détail de l'échec aval (service appelé, statut reçu, message) n'est jamais exposé dans le corps :
il est journalisé par `ProblemResponses` (`ERROR` à partir de 500, `DEBUG` en dessous) et corrélé
avec la réponse par `instance` (champ `mdc.requestId` de l'enregistrement JSON). Depuis W6-T03,
l'enregistrement ne porte que le statut public et la chaîne des noms de classe de la cause : ni
message d'exception ni pile, qui peuvent citer une donnée personnelle (voir
`docs/observability.md`, section 8).

### Unique chemin de traduction

La traduction d'un statut aval en vocabulaire d'échec de la passerelle se fait dans **une seule
classe**, `client/DownstreamResponseExceptionMapper` (`ResponseExceptionMapper`), enregistrée par
`@RegisterProvider` sur les trois interfaces clientes : les trois arêtes partagent donc exactement
le même chemin. Le choix du statut public se fait lui aussi dans un seul endroit,
`ProblemFactory.statusOf`.

### Relais fidèle des `FieldErrors`

Les erreurs par champ produites par un service validant (aval 400 ou 422) sont le **seul** fragment
de corps aval autorisé sur la surface publique : elles sont relayées telles quelles dans le membre
`errors` du document `Problem`, dans l'ordre reçu, sans réécriture de message ni de champ. Les deux
formes utilisées par les contrats internes sont acceptées : le tableau `FieldErrors`
(`[{"field","message","code"}]`), relayé entrée par entrée avec son `code`, et l'objet `errors`
d'un document problem (`{"champ":["message"]}`), aplati en une entrée par message. La forme
publique est le tableau `[{field, message, code}]` de `global/contracts/shared-schemas/FieldErrors.json`,
référence du contrat `souscription-web-to-souscription-gateway` (W7-T03 : la forme objet
`champ -> messages[]` utilisée jusqu'au plan 31 divergeait du contrat de référence et perdait le
`code` ; `ProvidedContractTest` la verrouille désormais). Les violations locales n'ont pas de `code`
et sont triées par champ puis par message. Un corps absent ou illisible ne change pas le statut :
le relais est simplement vide.

Un 422 aval est relayé avec le type `unprocessable-entity` et le titre `Requete non traitable`
(avant W7-T03, l'absence d'entrée dans les tables de `ProblemFactory` le présentait comme
`internal-error`). Le contrat public ne déclare pas 422 : c'est un statut relayé, couvert par
`ContractErrorMappingTest` (voir `docs/contract-validation.md`).

### Contrat OpenAPI

Chaque opération adossée à un aval (les neuf opérations hors `GET /api/v1/csrf-token`) déclare
`502`, `503` et `504` avec le schéma `Problem` ; `OpenApiContractTest` le verrouille.

### Confirmation : aucune nouvelle tentative automatique (W4-T02)

La confirmation (`POST /api/v1/drafts/{draftId}/confirmation`) est la **seule écriture** de la
passerelle et elle ne déclare **aucun `@Retry`**. `souscription-service` est idempotent sur la clé de
confirmation (contrainte unique `confirmation_key` héritée de la source, clé portée sur le réseau par
l'en-tête `Idempotency-Key`), mais du point de vue du transport l'issue d'un POST expiré est
inconnue : un réessai transparent rejouerait une action utilisateur sans son accord.

En conséquence, une expiration de délai sur la confirmation renvoie **504** avec un corps
`problem+json` dont le `detail` demande de **resoumettre la même clé de confirmation** ; la reprise
est donc pilotée par l'appelant, et la contrainte unique garantit qu'aucune souscription n'est
enregistrée en double. C'est le seul statut dont le `detail` porte une consigne de reprise au lieu de
reprendre le `title` (table `DETAILS` de `ProblemFactory`).

Une clé que le brouillon ne porte plus est un **409** relayé tel quel (`IdempotencyConflictException`)
et une clé structurellement invalide (vide, au-delà de 36 caractères) est un **400** produit par la
validation avant tout appel aval.

### Échecs au niveau du routeur (hors JAX-RS)

| Déclencheur                                   | Statut | `type` slug                |
|-----------------------------------------------|--------|----------------------------|
| Chemin hors `/api/v1` (aucune route trouvée)  | 404    | `not-found`                |
| Corps au-delà de `max-body-size`              | 413    | `payload-too-large`        |
| Type de média non supporté                    | 415    | `unsupported-media-type`   |

Ces réponses sont écrites par `RouterProblemHandler` via `RoutingContext`, donc le
`addHeadersEndHandler` de `SecurityHeaderFilter` (plan 11) y applique bien les cinq en-têtes de
sécurité. Exception : le rejet pour corps trop volumineux est décidé par la couche HTTP **avant**
que le routeur puisse échouer le contexte ; il est donc normalisé par `BodySizeProblemFilter`
(`@RouteFilter`, ordre `PAYLOAD_LIMIT = 50`, exécuté après l'installation du `headersEndHandler`),
qui compare le `Content-Length` déclaré à `quarkus.http.limits.max-body-size` et délègue le statut à
`ProblemFactory`.

## Composants

| Classe                                           | Rôle                                                |
|--------------------------------------------------|-----------------------------------------------------|
| `error/Problem`                                   | Représentation RFC 7807                             |
| `error/ProblemFactory`                            | Table des statuts et extraction des champs rejetés  |
| `error/ProblemResponses`                          | Média type, `instance`, `Retry-After`               |
| `error/ProblemMapper`                             | Dernier recours (`Throwable`) → 500                 |
| `error/WebApplicationProblemMapper`               | 400/403/404/409 quelle que soit la classe concrète  |
| `error/ValidationProblemMapper`                   | 400 avec `errors`                                   |
| `error/RequestBodyProblemMapper`                  | 400 sur corps JSON non liable (champ inconnu, type invalide) |
| `error/NotAllowedProblemMapper`                   | 405, dépendance déclarée du plan 13                 |
| `client/DownstreamResponseExceptionMapper`         | Unique traduction statut aval -> échec (3 clients)  |
| `error/DownstreamProblemMapper`                   | Relais 4xx / 502 / 503 et relais des `FieldErrors`  |
| `error/TimeoutProblemMapper`                      | 504                                                 |
| `error/CircuitBreakerProblemMapper`               | 503 + `Retry-After`                                 |
| `error/RouterProblemHandler`                      | Échecs du routeur Vert.x                            |
| `error/BodySizeProblemFilter`                     | 413 décidé avant le routeur (corps trop volumineux) |

## Vérification

`ProblemMapperTest` couvre les sept statuts de la source plus les classes d'échec aval, et asserte :
`Content-Type: application/problem+json`, un corps conforme au schéma publié, la présence des cinq
en-têtes de sécurité sur un 500 et sur un 404 de routeur, et zéro occurrence de `at fr.demo` ou
`Exception:` dans les corps d'erreur.

`DownstreamErrorMappingTest` (plan 20) pilote WireMock sur l'arête de simulation et couvre les six
issues aval : 400 porteur d'un corps `FieldErrors` (statut relayé et `errors` relayées telles
quelles), 404, 409, 500 (-> 502), 503 (-> 503 avec `Retry-After`) et une réponse retardée au-delà du
`read-timeout` (-> 504). Chaque cas asserte le média type `problem+json` et l'absence, dans le
corps, de tout texte écrit par le bouchon aval.

Côté Gherkin, les scénarios de transport appartenant à la passerelle sont
« Simulation d'un placement standard » et
« Parametres de simulation rejetes par le moteur de calcul » (`steps/SimulationSteps`), ainsi que
« Indisponibilite de l'enregistrement » (`steps/ConfirmationSouscriptionSteps`).
Les huit scénarios de calcul et de validation d'entrée de `features/simulation/simulation.feature`
portent `@souscription-simulation-service` et ne sont pas joués ici (voir
`features/SCENARIO_OWNERSHIP.md`).

`IdempotencyTest` (plan 18) couvre la confirmation : clé rejouée (201 puis 200, une seule écriture
aval), clé obsolète (409), clé structurellement invalide (400) et expiration de délai (504 dont le
`detail` demande la resoumission de la même clé).
