# Validation des contrats inter-applications - souscription-service (plan 42)

Porte bloquante unique : `fr.demo.souscription.contract.InterApplicationContractIT` (suite JUnit
Platform, execution failsafe dediee `inter-application-contracts`, resume
`target/failsafe-reports/failsafe-summary-contracts.xml`).

```
mvn -f souscription-service/pom.xml -B verify -Pcucumber -Dit.test=InterApplicationContractIT
```

Les contrats de `global/contracts/` sont lus depuis le disque et ne sont jamais modifies
(`git diff --name-only -- global/contracts/` vide).

## Resultat par contrat

| Contrat | Role | Tests de validation | Resultat |
| --- | --- | --- | --- |
| `souscription-gateway-to-souscription-service` (`openapi.yaml`) | Fournisseur (provider) | `OpenApiContractTest` (5), `ContractRepublishTest` (3), `ProviderContractSpecTest` (19) | PASS - 27/27 |
| `souscription-service-to-souscription-catalogue-service` (`openapi.yaml`, `resilience-contract.json`) | Consommateur (consumer) | `CatalogueClientContractTest` (3), `CatalogueResilienceTest` (5), `CatalogueContractStubTest` (4), `ResilienceContractReflectionTest` [catalogue] (5) ; bout en bout `RealSiblingsJourneyTest` | PASS - 17/17 ; bout en bout DIFFERE |
| `souscription-service-to-souscription-simulation-service` (`openapi.yaml`, `resilience-contract.json`) | Consommateur (consumer) | `SimulationClientContractTest` (14), `SimulationResilienceTest` (4), `SimulationContractStubTest` (5), `ResilienceContractReflectionTest` [simulation] (5) ; bout en bout `RealSiblingsJourneyTest` | PASS - 28/28 ; bout en bout DIFFERE |

Le parcours bout en bout `RealSiblingsJourneyTest` (`@Tag("real-siblings")`), commun aux deux
contrats consommes, est ignore par condition localement (`HORIZON_E2E_CATALOGUE_URL` /
`HORIZON_E2E_SIMULATION_URL` absents) : voir « Parcours contre les services soeurs reels ».

Run de la porte : 73 tests, 0 echec, 0 erreur, 1 ignore (parcours services reels).

## Ce qui est verifie

### Fournisseur (`ProviderContractSpecTest`, contre `target/openapi/openapi.json`)

- exactement 6 operation ids : `startDraft`, `getDraft`, `putDraftSubscriber`,
  `putDraftSimulation`, `confirmDraft`, `getSubscription` ;
- chemin, methode HTTP et ensemble des statuts de chaque operation identiques au contrat ;
- toute `$ref` du contrat (et des schemas atteints) resout dans `global/contracts/shared-schemas/` ;
  champs et types JSON des corps identiques, recursivement ;
- `initialAmount`, `monthlyAmount`, `contributed`, `finalCapital` (echelle 2) et `annualRate`
  (echelle 6) publies en chaines ; `durationYears` entier ; `birthDate` `string`/`date` ;
  `DraftView.status` enum a quatre valeurs ;
- `additionalProperties: false` : une propriete inconnue sur chacun des 4 corps de requete donne 400.

### Consommateurs (bouchons generes depuis les `openapi.yaml` geles, `ContractStub`)

- catalogue : « Consultation de la fiche d'un placement existant », « Le taux annuel est egalement
  expose en pourcentage », « Consultation de la fiche d'un placement inconnu » ;
- simulation : « Simulation d'un placement standard », « Parametres de simulation rejetes par le
  moteur de calcul », « Simulation avec une hypothese de progression nulle », « Simulation sans
  mensualite » ;
- le bouchon valide la requete du client et la reponse servie contre les schemas partages ; tout
  ecart echoue le test.

### Resilience (`ResilienceContractReflectionTest`)

`@Timeout`, `@Retry` (+ `@ExponentialBackoff` base/plafond, gigue, conditions non rejouables),
`@CircuitBreaker` (volume, ratio, delai, seuil de succes) et `@Bulkhead` de `CatalogueClient.findProduct`
et `SimulationClient.compute` lus par reflexion et compares a chaque `resilience-contract.json` ;
aucune surcharge MicroProfile Fault Tolerance dans `application.properties`.

## Obligation du consommateur : valeurs server-authoritative

`simulation.finalCapital` et `simulation.contributed` renvoyes par ce service sont
**server-authoritative** : obtenus du service de simulation au moment de la confirmation et
persistes (`final_capital`) dans la meme transaction que la souscription. L'appelant
(`souscription-gateway`, et toute application en aval) ne doit ni substituer une projection fournie
par le client (no client-supplied projection), ni les recompute localement, ni se rabattre
(fallback) sur une valeur client quand ce service est lent ou renvoie 503 : la reponse correcte est
alors l'erreur.

Ce service prouve qu'il calcule et persiste la valeur du fournisseur (contrat simulation ci-dessus,
et `RealSiblingsJourneyTest` contre le service vivant) ; il ne peut pas observer ce que l'appelant en
fait. Verificateur nomme : les controles de parite de `souscription-gateway` (porte de contrat du
gateway contre ce fournisseur). Regle detaillee : `API_VERSIONING.md`, section « Valeurs
autoritatives du serveur ».

## Parcours contre les services soeurs reels (etape 8)

Etat : **differe** localement - `HORIZON_E2E_CATALOGUE_URL` / `HORIZON_E2E_SIMULATION_URL` non
definis (aucun service soeur deploye accessible). Le test verifie, une fois active, le parcours
start -> subscriber -> simulation -> confirm et l'egalite du `final_capital` persiste avec la valeur
renvoyee par le service de simulation vivant. Ce differe doit etre leve avant la bascule du gateway
(proprietaire : equipe plateforme, definir les variables de depot ci-dessus sur l'environnement
d'integration).

## Pipeline

`.github/workflows/souscription-service.yml` : etape « Inter-application contract gate
(InterApplicationContractIT) » bloquante, puis archivage de `souscription-service/target/openapi/openapi.json`
(artefact `souscription-service-provider-contract`, avec le rapport failsafe de la porte).
Le parcours contre les services reels s'execute lorsque les variables de depot
`HORIZON_E2E_CATALOGUE_URL` et `HORIZON_E2E_SIMULATION_URL` sont definies.

## Changements de code induits

Deux annotations OpenAPI, sans effet sur le comportement, pour que l'artefact publie porte ce que
les schemas geles declarent deja :
- `DraftView.status` : `@Schema(enumeration = {STARTED, SUBSCRIBER_CAPTURED, SIMULATED, CONFIRMED})` ;
- `SubscriberInput.birthDate` : `@Schema(format = "date")`.
