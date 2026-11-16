# Statelessness et ordonnancement de deploiement — souscription-service (W2-T06)

Le service ne detient plus aucun etat par utilisateur en memoire : le brouillon de parcours vit
dans `subscriptions.subscription_draft` et les quatre anciens attributs de session HTTP sont
portes par la base. Aucun bean `@ApplicationScoped` du module ne declare de champ d'instance
mutable : seuls sont admis les beans `@Inject`-es, les valeurs `@ConfigProperty` et les constantes
`static final`. Le service est donc replicable sans affinite de session.

## Contrainte d'ordonnancement (mitigation R-002)

L'ordre suivant est **impose** et ne doit pas etre inverse :

1. **Test de charge (*load test*) du chemin de mise a jour du brouillon** (`PUT /drafts/{draftId}/subscriber`,
   `PUT /drafts/{draftId}/simulation`). Chaque etape du parcours devient une ecriture en base la
   ou la source ecrivait en memoire : la capacite doit etre mesuree avant de changer le routage.
   Mesure realisee par le plan `40` (test de charge).
2. **Seulement ensuite**, retrait de l'affinite de session (sticky sessions, *session affinity*) au
   niveau de
   `souscription-gateway`.
3. **Puis**, execution multi-replicas en round-robin : un parcours complet
   (`start` -> `subscriber` -> `simulation` -> `summary`) doit aboutir quelle que soit la
   repartition des requetes entre instances, y compris lorsque chaque requete frappe une instance
   differente. Mesure realisee par le plan `26` (multi-replicas).

Retirer l'affinite avant l'etape 1 expose directement la base a la charge du parcours sans aucune
mesure prealable : c'est le scenario de defaillance couvert par R-002.

## Seuil NFG-011

NFG-011 est satisfait lorsque les trois conditions suivantes sont simultanement vraies :

- **N instances sans affinite** : le service tourne avec N replicas, le routage est sans affinite
  de session ;
- **0 etat par utilisateur en memoire** : aucun champ d'instance mutable dans un bean
  `@ApplicationScoped`, aucune session HTTP, aucun type `Serializable`, aucun bloc `synchronized` ;
- **nombre de lignes de `subscription_draft` stable** dans le temps a trafic constant, garanti par
  la purge planifiee et surveille par les regles de `DRAFT_ALERTS.md`.

Les deux premieres conditions sont mesurees par le plan `26` (execution multi-replicas), la
capacite du chemin d'ecriture par le plan `40` (test de charge).
