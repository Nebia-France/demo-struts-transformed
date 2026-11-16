# Validation des contrats inter-applications (W7-T03)

Plan 32. Dans la source, les quatre arêtes ci-dessous étaient des appels Java en mémoire vérifiés par
le compilateur ; elles sont désormais des appels HTTP qu'aucun compilateur ne vérifie. Cette page
recense le test qui valide chacune d'elles. La liste des contrats et leur rôle sont ceux de
`global/contracts/INTER_APPLICATION_CONTRACTS.json` (les deux arêtes
`souscription-service-to-*` n'impliquent pas la passerelle et sont validées par `souscription-service`).

contractId | Role (rôle de la passerelle) | Spec path | Validating test | Status
---|---|---|---|---
| `souscription-web-to-souscription-gateway` | fournisseur (`/api/v1`) | `global/contracts/souscription-web-to-souscription-gateway/openapi.yaml` | `contract/ProvidedContractTest` (+ `contract/ContractErrorMappingTest`) | PASS |
| `souscription-gateway-to-souscription-catalogue-service` | consommateur (`/v1`) | `global/contracts/souscription-gateway-to-souscription-catalogue-service/openapi.yaml` | `contract/ConsumedContractTest[catalogue]` (+ `ContractErrorMappingTest` 404, 502) | PASS |
| `souscription-gateway-to-souscription-simulation-service` | consommateur (`/v1`) | `global/contracts/souscription-gateway-to-souscription-simulation-service/openapi.yaml` | `contract/ConsumedContractTest[simulation]` (+ `ContractErrorMappingTest` 422, 502, 504) | PASS |
| `souscription-gateway-to-souscription-service` | consommateur (`/v1`) | `global/contracts/souscription-gateway-to-souscription-service/openapi.yaml` | `contract/ConsumedContractTest[subscription]` (+ `ContractErrorMappingTest` 404, 503) | PASS |

Tous ces tests s'exécutent dans `mvn -f souscription-gateway/pom.xml clean verify -Pcucumber` et dans
l'étape `contract` de `souscription-gateway/.ci/pipeline.yml`. Seules les quatre lignes de contrat
ci-dessus commencent par `|` (registre contrôlé par `grep -c "^|"` = 4) ; les en-têtes et les autres
tableaux de la page sont écrits sans `|` initial, forme équivalente en Markdown GFM.

## Contrat fourni : `ProvidedContractTest`

La référence est le fichier `openapi.yaml` du contrat et les schémas `shared-schemas/*.json` qu'il
référence (souscription-web en génère ses modèles TypeScript).

- `target/openapi.yaml` est produit par le build (`quarkus.smallrye-openapi.store-schema-directory`),
  est un document OpenAPI 3 sans `$ref` pendante et publie la même surface `/api/v1` que `/q/openapi`.
- La surface `/api/v1` publiée est **exactement** celle de la référence : dix opérations, mêmes
  chemins, méthodes et `operationId` ; aucune opération manquante, aucune opération publique en plus.
- Par opération : mêmes paramètres de chemin, même exigence `X-CSRF-Token` (schéma `csrfToken`,
  `apiKey` en en-tête), et chaque réponse déclarée par la référence est publiée avec son type de média
  (la passerelle publie en plus 405, 502 et 504, ce qui reste compatible).
- Les schémas publiés portent les membres des schémas partagés ; `Problem` n'a que des membres de
  `FieldErrors.json` et son `errors` est le tableau `{field, message, code}`.
- Les réponses réelles de tout le parcours (jeton CSRF, catalogue, produit, simulation, brouillon,
  souscripteur, simulation du brouillon, confirmation, souscription) et des erreurs 400, 403 et 404 sont
  validées contre le schéma de référence de leur statut.
- Format de fil gelé de la vague 1 : montants en chaîne d'échelle 2, taux en chaîne d'échelle 6,
  `durationYears` entier, dates et instants ISO-8601, UTF-8, `application/problem+json`.

## Contrats consommés : `ConsumedContractTest`

Un cas paramétré par aval (`catalogue`, `simulation`, `subscription`) :

1. l'interface `@RegisterRestClient` couvre exactement les opérations du contrat (méthode, chemin de
   base + gabarit), avec les paramètres de chemin déclarés, chaque en-tête requis
   (`Idempotency-Key` sur la confirmation) et aucun en-tête non déclaré ;
2. les types de charge utile (requête et réponse, `Uni`/`RestResponse` déballés) ont exactement les
   membres du schéma du contrat, membres requis compris, avec un type JSON compatible, récursivement ;
3. les bouchons WireMock de `src/test/resources/wiremock/` sont identiques octet pour octet à la sortie
   de `src/test/scripts/generate-stubs.sh` (plan 29) régénérée dans `target/contract-stubs/` ;
4. un appel réel du client est servi par un bouchon dérivé du contrat (métadonnée `contract`), sans
   statut d'erreur, et le corps de requête émis par la passerelle est valide pour le schéma du contrat.

## Correspondances d'erreurs : `ContractErrorMappingTest`

Aval | Réponse aval | Réponse publique | Origine du bouchon
---|---|---|---
catalogue / souscription | 404 | 404 `not-found` | bouchon généré (NotFound)
simulation | 422 avec `errors` | 422 `unprocessable-entity`, `errors` relayées telles quelles (`code` compris) | surcharge du test
catalogue / simulation | 500 | 502 `downstream-error` | bouchon généré (`@@forced-error@@`) / surcharge
souscription | 503 | 503 `service-unavailable` + `Retry-After: 30` | surcharge du test
simulation | délai > `read-timeout` | 504 `downstream-timeout` | surcharge du test

Chaque réponse est `application/problem+json`, conforme à `FieldErrors.json`, avec `status` égal au
statut HTTP, et ne laisse passer aucun texte aval. Seule la correspondance d'erreur est vérifiée ici :
les règles de calcul de la simulation ne sont pas dupliquées dans la passerelle.

## Détecteur de rupture : `src/test/scripts/contract-breaking-check.sh`

Sur une copie du module et de `global/contracts/` placée sous `souscription-gateway/target/`, le
script renomme le membre requis `annualRate` de `shared-schemas/SimulationComputeRequest.json`
(requête de `POST /v1/simulations`) en `annualInterestRate`, régénère les bouchons de la copie,
lance les tests de contrat de la copie (`--full` : `clean verify`) et sort en 0 seulement si cette
construction **échoue** sur `ConsumedContractTest` en nommant le membre renommé. Mesure du
14/11/2026 : construction de la copie en échec (exit 1), `thePayloadTypesMatchTheContractSchemas[simulation]`
signale `required member 'annualInterestRate' has no counterpart in SimulationComputeRequest` et
l'appel réel est refusé par le bouchon régénéré (400) ; script en sortie 0.

## Écarts relevés et corrigés par ce plan

- **Forme de `errors` dans `problem+json`** : la passerelle publiait un objet `champ -> messages[]`,
  la référence (`FieldErrors.json`) déclare un tableau `[{field, message, code}]` et le `code` du
  service validant était perdu. La passerelle émet et relaie désormais la forme de référence
  (`error/Problem`, `ProblemFactory`, `DownstreamResponseExceptionMapper`, schéma `Problem` de
  `META-INF/openapi.yaml`) ; voir `docs/error-mapping.md`.
- **422 relayé** : il était relayé avec le titre et le type `internal-error` faute d'entrée dans les
  tables de `ProblemFactory` ; il porte désormais `unprocessable-entity`.

## Points ouverts (non bloquants)

- La référence ne déclare pas 422 sur `POST /simulations` alors que la passerelle relaie un 422 aval ;
  le contrat interne de simulation ne déclare lui-même que 400. À trancher avec l'équipe simulation
  (déclarer 422 dans les deux contrats ou ne jamais l'émettre).
- Les schémas générés par la passerelle ne listent pas tous les membres `required` des schémas
  partagés (par exemple `ProductSummary`, `SimulationResult`). Les réponses réelles sont conformes à la
  référence, ce qui est vérifié ; seul le document généré est moins strict.
