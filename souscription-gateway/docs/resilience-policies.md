# Politiques de tolérance aux pannes par arête (W5-T01)

La source n'avait ni disjoncteur, ni réessai, ni délai d'attente, ni repli : la couche web appelait
la couche métier **en processus** et ne pouvait pas échouer partiellement. Trois dépendances série à
99,9 % composent à ≈ 99,7 % : ces politiques sont une **exigence** (AG-014), pas une optimisation.

Chaque arête est franchie par un bean `@ApplicationScoped` de
`fr.demo.souscription.gateway.client` qui enveloppe son client REST. **Les ressources injectent ces
beans, jamais les clients bruts** : aucun appel sortant ne peut échapper aux politiques.

| Dépendance | Bean | Opération | Délai (ms) | Réessais | Disjoncteur | Repli |
|---|---|---|---|---|---|---|
| souscription-catalogue-service | `CatalogueGateway` | `list` (lecture) | 3000 | 2 (délai 100 ms, gigue 50 ms) | partagé catalogue : 20 req. / 50 % / 10 s | 503 + `Retry-After` |
| souscription-catalogue-service | `CatalogueGateway` | `detail` (lecture) | 3000 | 2 (délai 100 ms, gigue 50 ms) | partagé catalogue | 503 + `Retry-After` |
| souscription-simulation-service | `SimulationGateway` | `compute` (calcul pur) | 3000 | 1 | partagé simulation : 20 req. / 50 % / 10 s | — |
| souscription-service | `SubscriptionGateway` | `startDraft` (écriture) | 5000 | **aucun** | partagé souscription : 20 req. / 50 % / 10 s | — |
| souscription-service | `SubscriptionGateway` | `getDraft` (lecture) | 5000 | 1 | partagé souscription | — |
| souscription-service | `SubscriptionGateway` | `putSubscriber` (écriture) | 5000 | **aucun** | partagé souscription | — |
| souscription-service | `SubscriptionGateway` | `putSimulation` (écriture) | 5000 | **aucun** | partagé souscription | — |
| souscription-service | `SubscriptionGateway` | `confirm` (écriture) | 5000 | **aucun** | partagé souscription | **aucun** |
| souscription-service | `SubscriptionGateway` | `getSubscription` (lecture) | 5000 | 1 | partagé souscription | — |

« Partagé » désigne la déclaration : un `@CircuitBreaker` est posé **une fois par bean**, donc une
fois par dépendance, et l'état est tenu par méthode. Un aval en panne est ainsi isolé des deux
autres : le catalogue qui tombe n'ouvre pas le circuit de la confirmation.

## Décisions

1. **Un délai d'attente sur chaque appel sortant.** Sans lui, une requête publique attend aussi
   longtemps que la dépendance, ce qui épuise les connexions entrantes. Les budgets reflètent le
   travail attendu : 3000 ms pour une lecture de catalogue ou une projection, 5000 ms côté
   souscription-service, dont la confirmation couvre une lecture produit, la simulation distante et
   l'écriture. **Retirer un délai n'est jamais un repli acceptable** ; seule sa valeur est ajustable.
2. **Réessai sur les lectures idempotentes seulement.** Rejouer une lecture est sans conséquence ;
   rejouer une écriture dont le sort est inconnu peut dupliquer une action de l'utilisateur, même si
   le fournisseur est idempotent sur la clé (W4-T02). La confirmation ne déclare donc **aucun**
   `@Retry` : l'expiration remonte en 504 et le corps demande de resoumettre **la même** clé.
   Les réessais sont bornés et espacés d'une gigue, pour ne pas transformer un incident aval en
   avalanche de la passerelle.
3. **Ce que compte le disjoncteur.** Uniquement les échecs qui disent la dépendance injoignable ou
   trop lente (`IOException`, `ProcessingException`, `TimeoutException`). Un aval qui répond
   promptement un 4xx rend un verdict sur la requête, pas une panne : un 404 de code produit inconnu
   n'ouvre aucun circuit. Un 5xx rendu promptement garde son statut public gelé (502, ou 503 relayé),
   car c'est la table de [`error-mapping.md`](error-mapping.md) qui en décide, pas l'état du circuit.
4. **Repli uniquement là où une réponse dégradée est acceptable.** Le catalogue injoignable ou son
   circuit ouvert donne `DownstreamUnavailableException`, soit **503 + `Retry-After`** : le client
   sait que le refus est transitoire. Aucun repli sur la simulation (des chiffres inventés avant un
   engagement seraient pires qu'une erreur) et surtout **aucun sur la confirmation** : un repli
   silencieux sur une écriture masquerait une perte de données.
5. **Tous les seuils sont redéclarés en configuration** dans `application.properties` sous la forme
   `<classe>/<méthode>/<politique>/<paramètre>=${VARIABLE:défaut}`, pour qu'un incident se traite
   par variable d'environnement et non par livraison de code.

## Statuts publics produits par ces politiques

| Événement | Statut | En-tête |
|---|---|---|
| Expiration de délai (`TimeoutException`) | 504 | — |
| Circuit ouvert (`CircuitBreakerOpenException`) | 503 | `Retry-After: 30` |
| Catalogue injoignable (repli, `DownstreamUnavailableException`) | 503 | `Retry-After: 30` |

Les trois passent par les mappeurs du paquet `error` (plan 20) ; `DownstreamUnavailableException`
est une `WebApplicationException` en 503, donc `ProblemResponses` lui ajoute `Retry-After`.

## Vérification

`FaultTolerancePolicyTest` verrouille les quatre propriétés sur la surface HTTP réelle et sur le
journal de requêtes WireMock :

- le délai d'attente de la confirmation se déclenche à sa borne, bien avant les 7 s de l'aval ;
- le journal ne montre **qu'un seul POST** de confirmation : l'écriture n'est pas réessayée ;
- la lecture catalogue produit `1 + maxRetries` tentatives pour un appel public ;
- le disjoncteur du catalogue s'ouvre une fois le volume et le ratio atteints — la réponse devient
  alors le 503 + `Retry-After` du repli — puis se referme après son délai ;
- un échec de la simulation reste un 502 : aucun repli ne le masque.

Le test abaisse les seuils du disjoncteur par un profil de test, ce qui est exactement l'usage de
la redéclaration en configuration ; les valeurs de production ne sont pas touchées.

## Cloisonnement (W5-T03)

À ces politiques s'ajoute un `@Bulkhead` par méthode de passerelle, qui borne la concurrence
autorisée sur chaque arête ; les tailles, la composition parallèle des appels indépendants et le
budget de latence par point d'entrée sont décrits dans [`latency-budget.md`](latency-budget.md).
Un rejet de bulkhead est un refus **transitoire** : 503 + `Retry-After`, comme un circuit ouvert.
