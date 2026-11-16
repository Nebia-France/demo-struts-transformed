# Modèle sans état du gateway (W3-T01)

Plan `14-souscription-gateway-stateless-no-session`. Le gateway ne conserve **aucun état
conversationnel** : ni `HttpSession`, ni portée `@SessionScoped`, ni cache par utilisateur, ni
collection statique mutable. Son `ownedData` est vide dans la topologie cible.

## 1. État de session du source et son nouveau propriétaire

Source de référence : `src/main/java/fr/demo/souscription/action/SubscriptionAction.java`
(`request.getSession()`, bloc `synchronized (session)`), `src/main/webapp/WEB-INF/web.xml`
(`session-config` 20 min, `cookie-config/http-only`, `tracking-mode COOKIE`) et
`src/main/webapp/META-INF/context.xml` (`SameSite=lax`).

| Attribut de session (source) | Écrit à l'étape | Contenu | Nouveau propriétaire |
| --- | --- | --- | --- |
| `draft` (`Draft`) | 1 `/commencer` | brouillon complet : produit, souscripteur, simulation, `confirmationKey` | table de brouillons dédiée de `souscription-service` (OD-005), lue/écrite via `/api/v1/drafts/{draftId}` |
| `draft.product` (`Product`) | 1 `/commencer` | produit sélectionné (code, libellé, profil, taux annuel) | brouillon persisté (`souscription-service`) ; le taux autoritatif reste résolu auprès de `souscription-catalogue-service` |
| `draft.subscriber` (`Subscriber`) | 2 `/souscripteur` | identité du souscripteur | brouillon persisté ; saisie en cours conservée dans le store à signaux de la SPA (TQ-033) |
| `draft.simulation` (`Simulation`) | 3 `/simulation` | montants, durée, capitaux projetés | brouillon persisté ; projection non engageante recalculée sans état par `POST /api/v1/simulations` |
| `draft.confirmationKey` | 1 puis à chaque mise à jour | clé d'idempotence de la confirmation | brouillon persisté, exposée dans `DraftView.confirmationKey` et rejouée par le client dans `ConfirmationRequest` |
| `completed` (`Subscription`) | 5 `/confirmer` | souscription confirmée affichée par `/confirmation` | table `subscriptions` de `souscription-service`, lue via `GET /api/v1/subscriptions/{reference}` |
| `completedReference` | 5 `/confirmer` | référence servant au contrôle de rejeu | référence retournée par la confirmation et détenue par la SPA ; le rejeu est tranché par l'idempotence côté `souscription-service` |
| `completedKey` | 5 `/confirmer` | clé comparée au `confirmationKey` du formulaire pour autoriser un rejeu | idempotence sur `confirmationKey` dans `souscription-service` (201 à la première confirmation, 200 au rejeu) |
| `JSESSIONID` (cookie de suivi, 20 min) | 1re requête | identité de conversation | **supprimé** ; l'identité de conversation est le `draftId` porté par l'URI, fourni par le client |
| Jeton CSRF en attribut de session | formulaires `*.do` | protection CSRF liée à la session | cookie double-submit émis par le gateway (plan 15/16), sans session |

Côté SPA (TQ-033), le store à signaux ne conserve que le `draftId`, le `confirmationKey` courant et
la saisie non encore envoyée ; toute donnée engageante est relue depuis `GET /api/v1/drafts/{draftId}`.

## 2. Identifiant de brouillon porté par le client

Toutes les routes de brouillon prennent `draftId` en paramètre de chemin, typé `java.util.UUID`
dans `DraftResource` et dans `SubscriptionClient` : un identifiant malformé est rejeté
structurellement en **400 `application/problem+json`** par les mappers du plan 12, sans appel aval.

Le comportement par défaut de Jakarta REST transforme un échec de conversion de paramètre de chemin
en `404`, indiscernable d'un brouillon inconnu mais bien formé. `UuidParamConverterProvider` lève
donc une `BadRequestException`, ce qui donne le `400` attendu
(`NoSessionCookieTest#aMalformedClientSuppliedDraftIdIsRejectedAs400`), le `404` restant réservé à
un UUID valide mais inexistant côté `souscription-service`.

* `GET /api/v1/drafts/{draftId}`
* `PUT /api/v1/drafts/{draftId}/subscriber`
* `PUT /api/v1/drafts/{draftId}/simulation`
* `POST /api/v1/drafts/{draftId}/confirmation`

`POST /api/v1/drafts` est la seule route qui crée un `draftId` ; elle le renvoie dans le corps et
dans l'en-tête `Location`.

## 3. Séquencement d'étapes retiré du gateway

Le source enchaînait des gardes « étape N exige étape N-1 » :
`draft == null → /catalogue.do?notice=parcours`, `draft.getSubscriber() == null → /souscripteur.do`,
`draft.getSimulation() == null → /simulation.do`, plus le rejet `409` sur `confirmationKey` obsolète.

Ces règles **ne sont pas reproduites** dans le gateway :

* `souscription-service`, propriétaire du brouillon, est l'autorité : brouillon incomplet ou clé
  obsolète ⇒ `409`, identifiant inconnu ⇒ `404`, relayés tels quels par le gateway (plan 12) ;
* le routeur de la SPA assure le confort de navigation (redirection vers l'étape manquante).

Le gateway se limite à la validation structurelle (`@Valid`, bornes de taille) et au relais.

## 4. Revue d'absence d'état (critères 2, 4 et 5)

| Vérification | Résultat |
| --- | --- |
| `grep -rn "SessionScoped\|HttpSession\|getSession()" src` | aucune occurrence |
| `grep -rn "synchronized" src/main/java` | aucune occurrence (le `synchronized (session)` du source disparaît avec la session) |
| Collection statique mutable dans `src/main/java` | aucune : les seuls champs `static` collectifs sont `Map.of` / `List.of` / `Set.of` immuables (`ProblemFactory`, `RouterProblemHandler`, `CatalogueClientStub`) |
| Beans à portée conversationnelle | aucun : filtres et ressources sont sans champ mutable, `GatewayConfig` est une projection de configuration immuable |
| Cache par utilisateur | aucun |

Exception connue et bornée : `SubscriptionClientStub` détient deux `ConcurrentHashMap` (brouillons,
souscriptions) pour simuler l'aval en l'absence de `souscription-service`. Ce n'est pas un état
conversationnel (clé = `draftId` / référence, pas d'identité d'utilisateur), c'est un faux
propriétaire de données. Il porte le marqueur `SWITCH-TO-REAL-CLIENT` et est remplacé par le client
REST réel au plan 17 ; il n'existe donc plus d'état en mémoire dans le gateway à ce moment-là.
Tant qu'il est en place, le contrôle sans affinité de la section 6 n'est significatif qu'avec le
client réel (plan 24).

Aucune session n'est créée : le module n'embarque ni `quarkus-undertow` ni extension servlet, et
Quarkus REST ne crée pas de session HTTP. Il n'y a donc pas de `session-config` à désactiver ;
l'absence d'émission est **vérifiée** plutôt que configurée, par
`src/test/java/fr/demo/souscription/gateway/edge/NoSessionCookieTest.java` qui parcourt les routes
du contrat et échoue sur tout `Set-Cookie` de nom de type session (`JSESSIONID`, `quarkus-session`,
`session`, `sessionid`, `sid`, `sess`), y compris lorsqu'un `JSESSIONID` est présenté par le client
(CFG-006). Après le plan 16, le seul cookie émis est le cookie CSRF.

## 5. Conséquence comportementale assumée : 403 « session expirée » → TTL du jeton CSRF

Le legacy `src/test/java/fr/demo/souscription/web/SubscriptionIT.java` asserte un **403 sur session
expirée** : passé 20 minutes d'inactivité, la session disparaissait et toute soumission était
refusée. Avec la session, cette assertion disparaît : un `draftId` reste valide aussi longtemps que
`souscription-service` conserve le brouillon.

**Obligation successeur (bloquante pour le plan 31)** : la borne temporelle doit être rétablie par
la **durée de vie bornée du jeton CSRF** du plan 15 (TTL aligné sur les 20 minutes du source), et le
plan 31 doit porter l'assertion correspondante — jeton CSRF expiré ⇒ `403`
`application/problem+json` sur toute route mutante. Sans cette assertion successeur, la régression
de sécurité (perte de la borne d'inactivité) passerait inaperçue.

## 6. Contrôle sans affinité (NFG-012)

`src/test/scripts/no-affinity-check.sh` déroule un parcours complet en alternant à chaque requête
entre deux instances (`PORT_A`=8080, `PORT_B`=8081) sans affinité de session, et échoue sur toute
réponse 4xx/5xx. Le script est exécuté pour de vrai au plan 24 ; ici seule sa validité syntaxique
est exigée (`bash -n`).

```bash
PORT_A=8080 PORT_B=8081 bash souscription-gateway/src/test/scripts/no-affinity-check.sh
```
