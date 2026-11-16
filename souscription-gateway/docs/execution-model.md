# Modele d'execution non bloquant (W5-T02)

Portee : module `souscription-gateway`. Critere NFG-009.

## Regle

Aucune construction bloquante ne doit figurer sur un chemin de requete dans
`souscription-gateway/src/main/java` : ni `synchronized`, ni `wait()` / `notify()`, ni
`Thread.sleep`, ni `.await()`, ni `CountDownLatch`, ni appel JDBC, ni lecture/ecriture `Files.`,
ni client HTTP bloquant.

Toutes les routes exposent `Uni<...>` et toutes les aretes aval passent par le client REST reactif
(`SimulationClient`, `CatalogueClient`, `SubscriptionClient`) : la composition reactive est le seul
mode d'execution de la passerelle.

## Pourquoi

Dans la source, `fr.demo.souscription.web.SecurityFilter` protegeait le jeton CSRF par
`synchronized (session)`. Ce verrou n'etait correct que sous le modele thread-par-requete : la
session etait locale a la JVM et bloquer un thread de travail ne coutait qu'une requete. Sous
Vert.x, le meme verrou immobilise la boucle d'evenements, donc **toutes** les requetes multiplexees
sur ce thread.

Le verrou n'est pas perdu, il a un successeur nomme. L'invariant qu'il protegeait est
**un seul jeton CSRF par session** : deux requetes concurrentes du meme utilisateur ne devaient pas
generer deux jetons et s'invalider mutuellement. Cet invariant est desormais preserve par
l'**emission idempotente et sans verrou** du plan 15 : le jeton est sans etat serveur, signe et
derive de maniere deterministe, donc deux emissions concurrentes produisent un resultat equivalent
et il n'y a plus d'etat mutable partage a proteger par exclusion mutuelle.

| Construction supprimee | Invariant source | Successeur |
| --- | --- | --- |
| `synchronized (session)` de `SecurityFilter` | un seul jeton CSRF par session | emission CSRF idempotente et sans verrou (plan 15, `CsrfTokenService` / `CsrfCookieIssuer`) |

## Filtres d'arete : sans etat et sans verrou

`CsrfDoubleSubmitFilter`, `MethodAllowListFilter`, `SecurityHeaderFilter`, `ResponseContextFilter`
et `BodySizeProblemFilter` ne lisent que le `RoutingContext` (en-tetes, cookies, chemin, methode),
comparent, puis appellent `rc.next()` ou terminent la reponse. Aucun champ mutable d'instance,
aucun cache, aucun verrou. `CsrfTokenService` ne detient que des constantes (`SecureRandom`,
encodeur Base64) et la configuration immuable.

Les compteurs de rejet (`RejectionMetrics`, W6-T02) appeles par ces filtres sont des compteurs
Micrometer sans verrou (increment atomique) : ils n'introduisent aucune attente sur la boucle
d'evenements. La seule lecture de fichier de l'observabilite, `CertificateExpiryMetrics` (lecture
des magasins mTLS pour la jauge d'expiration), s'execute une fois au `StartupEvent`, sur le thread
principal, avant que la boucle d'evenements ne serve la moindre requete.

## Methodes `@Blocking`

Aucune. Le tableau ci-dessous est la liste exhaustive des methodes autorisees a s'executer sur un
thread de travail ; il est vide, et `ExecutionModelTest#everyBlockingAnnotationIsDocumented`
echoue si une annotation `@io.smallrye.common.annotation.Blocking` est ajoutee sans etre inscrite
ici au format `<Classe>#<methode>` avec sa justification.

| Methode (`<Classe>#<methode>`) | Raison documentee |
| --- | --- |
| _(aucune)_ | — |

## Detection

`src/main/resources/application.properties` :

```properties
quarkus.vertx.max-event-loop-execute-time=2S
quarkus.vertx.warning-exception-time=2S
%test.quarkus.vertx.max-event-loop-execute-time=2S
%test.quarkus.vertx.warning-exception-time=2S
```

Vert.x emet alors `Thread vertx-eventloop-thread-N has been blocked for N ms` des qu'une tache
depasse 2000 ms sur une boucle d'evenements.

## Critere de sortie (NFG-009)

**0** avertissement `Thread ... has been blocked for N ms` dans le journal de l'application sous la
charge de reference, seuil de detection **2000 ms**.

Garde CI, etape `event-loop-gate` de `.ci/pipeline.yml`, apres l'etape de charge :

```bash
! grep -q "has been blocked" target/application.log
```

## Limite de la verification

Le blocage de la boucle d'evenements **ne se voit pas** en test unitaire : la concurrence y est de
une requete a la fois, donc un appel bloquant y repond normalement. Seul le test de charge du plan
24 le detecte de maniere fiable. `ExecutionModelTest` compense cette cecite par un scan statique du
code source principal : il est la regle de non-regression, pas une preuve d'absence de blocage.
