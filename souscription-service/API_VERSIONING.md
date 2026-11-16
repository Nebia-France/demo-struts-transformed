# Versionnement de l'API et publication des documents OpenAPI (W3-T09)

## Strategie confirmee

| Portee | Prefixe | Expose par | Accessibilite |
| --- | --- | --- | --- |
| Facade publique | `/api/v1` | `souscription-gateway` | Internet, derriere l'ingress partage avec la SPA (meme origine) |
| Contrat interne | `/v1` | `souscription-service` (ce module), `souscription-catalogue-service`, `souscription-simulation-service` | Cluster uniquement, mTLS, aucune route externe |

Chaque service backend expose ses operations sous `/v1`, en interne au cluster. Le `/v1` n'est pas
visible des clients finaux : la passerelle le rend sous `/api/v1`. Une rupture de contrat se traduit
par un nouveau prefixe (`/v2`) et non par une modification en place de `/v1` ; les ajouts
compatibles (champ optionnel, nouvelle operation) restent dans `/v1`.

Les ressources de ce module portent le chemin complet (`@Path("/v1/drafts")`,
`@Path("/v1/subscriptions")`) ; le document OpenAPI declare par ailleurs les trois serveurs
`https://souscription-service.<env>.svc.cluster.local:8443/v1`. Le prefixe serait donc present deux
fois dans le document publie, et la concatenation base + chemin designerait `/v1/v1/drafts`, adresse
que le service ne sert pas : `VersionPrefixFilter` (`mp.openapi.filter`) retire le prefixe des
chemins du **document** uniquement, de sorte que le document publie est identique au contrat gele
(chemins sans prefixe, prefixe dans la base serveur). Le routage HTTP reel, porte par les
annotations `@Path`, est inchange.

## Ou chaque document est publie

| Document | Emplacement |
| --- | --- |
| Contrat interne de ce service, a l'execution | `GET /q/openapi` (et `/q/openapi?format=json`) sur le port HTTP du service |
| Contrat interne de ce service, artefact de pipeline | `souscription-service/target/openapi/openapi.json` et `.yaml`, archives par le workflow `.github/workflows/souscription-service.yml` sous le nom `souscription-service-openapi` |
| Reference gelee consommee par la passerelle | `global/contracts/souscription-gateway-to-souscription-service/openapi.yaml` — jamais modifiee par ce module |
| Contrat public `/api/v1` | publie par `souscription-gateway` (reference gelee : `global/contracts/souscription-web-to-souscription-gateway/openapi.yaml`) |

## Porte de non-regression (NFG-019)

`OpenApiContractTest` echoue le build des que le document genere s'ecarte du contrat gele sur :

- l'ensemble des `operationId` (exactement les six du contrat) ;
- le chemin et la methode HTTP de chaque operation ;
- l'ensemble des statuts de reponse declares par operation ;
- la liste des serveurs publies (adresses internes au cluster, jamais une base localhost) ;
- la resolution de toutes les references `$ref` du document publie.

Le workflow execute ce test puis rejoue la comparaison des ensembles operation/statut sur le
document publie, de sorte que la porte existe aussi hors de la JVM de test. La comparaison porte sur
les ensembles observables et non sur un `diff` textuel des deux fichiers : le contrat gele reference
`shared-schemas/*.json` que le generateur inline, un diff brut serait donc toujours rouge.

## Valeurs autoritatives du serveur (obligation du consommateur)

Les valeurs `simulation.finalCapital` et `simulation.contributed` renvoyees par ce service sont
**server-authoritative** : elles sont recalculees par le service puis persistees dans la meme unite
de travail que l'ecriture de la souscription (comme `SubscriptionService.confirm` le faisait en
process dans la source, BASELINE.md §4.3). Le consommateur — `souscription-gateway` en premier lieu,
et toute application en aval — **ne doit pas** :

- substituer a ces valeurs une projection calculee cote client (no client-supplied projection) ;
- les recalculer localement (no client recompute) pour les reemettre comme si elles venaient du
  service ;
- se rabattre sur une valeur fournie par le client lorsque ce service est lent ou renvoie 503 : la
  reponse correcte est alors l'erreur, pas un chiffre que ce service n'a jamais calcule.

Dans la cible, ce calcul est un appel distant et la valeur circule sur le reseau : le fournisseur ne
peut plus observer ce que l'appelant en fait. Publier la regle est le seul controle disponible ici.
La verification de bout en bout appartient aux controles de parite de `souscription-gateway` et a la
validation de contrat du plan 42 (tests cote consommateur rejoues contre le fournisseur vivant) ;
elle est enregistree dans
`apps/souscription-service/parity-checks/19-souscription-service-openapi-v1-contract-parity/test-gaps.md`.
