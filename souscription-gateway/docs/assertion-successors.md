# Successeurs des assertions de `SubscriptionIT` (W7-T02, CFG-013 / R-016)

Source : `src/test/java/fr/demo/souscription/web/SubscriptionIT.java` (tier hérité, **conservé sans
modification**). Ce test pilote HTTP sur les pages JSP et parse le HTML avec jsoup pour extraire le
champ caché `csrf`. Chaque assertion reçoit ci-dessous un successeur nommé : une méthode de test de
la gateway (REST Assured, JSON, jeton lu dans le cookie `XSRF-TOKEN` et renvoyé dans l'en-tête
`X-CSRF-Token`, trois services amont simulés par WireMock), ou l'application sœur propriétaire selon
la matrice du plan 30 (`features/SCENARIO_OWNERSHIP.json`).

Règles de lecture :

- « Gateway » = classe/méthode sous `souscription-gateway/src/test/java/fr/demo/souscription/gateway/`.
- « Cucumber gateway » = scénario exécuté par `GatewayCucumberTest`
  (`mvn -f souscription-gateway/pom.xml verify -Pcucumber`, gate obligatoire à partir de ce plan).
- « Propriétaire » = application sœur qui porte l'assertion (le rendu HTML, la validation métier et
  la persistance ne sont plus des responsabilités de la gateway).
- Chaque test négatif de la gateway (`CsrfEdgeTest`, `SubscriptionItSuccessorTest`) vérifie aussi le
  compteur `gateway.rejections{status,reason}` correspondant (+1, pas d'autre série) et l'unique
  entrée de journal JSON corrélée par `requestId` = `instance` du problème (`RejectionObservation`).
  Les refus décidés après les filtres d'edge (405 de ressource, 400, 404, 409, 502/503 aval) ne
  ne relèvent pas d'une série `gateway.rejections` (ensemble fermé du plan 26) : le test vérifie que pas une
  série ne bouge et qu'exactement une entrée JSON `ProblemResponses` (plan 27) est écrite, corrélée à
  `instance` et portant `status=<code>` (`RejectionObservation#assertSingleProblemEntry`).

## Test `catalogueAndAllProductsRenderWithRealJakartaTaglibs`

| # | Ligne | Assertion source | Successeur |
| --- | --- | --- | --- |
| 1 | 24 | `GET /catalogue.do` → 200 | Gateway `SubscriptionItSuccessorTest#catalogueListsThreeProductsEachReadable`, `GatewayApiRoutesTest#listProductsAnswers200` |
| 2 | 25 | 3 lignes dans `table.catalogue` | Gateway `SubscriptionItSuccessorTest#catalogueListsThreeProductsEachReadable` (`$` de taille 3) ; rendu du tableau → propriétaire `souscription-web` |
| 3 | 26 | texte « Extranet Financière Horizon » | Propriétaire `souscription-web` (gabarit de l'en-tête SPA) |
| 4 | 27 | texte « Horizon Équilibre » | Gateway `WireFormatTest#accentedLabelSurvivesTheHttpRoundTrip` (libellé accentué relayé à l'octet près) ; affichage → `souscription-web` |
| 5 | 28 | texte « réalisez une simulation » | Propriétaire `souscription-web` (texte d'invite du catalogue) |
| 6 | 29 | texte « Financière Horizon — Épargne » | Propriétaire `souscription-web` (pied de page) |
| 7 | 30 | zéro `.demo-strip`, `.side-box` | Propriétaire `souscription-web` (pas de bandeau de démonstration dans la SPA) |
| 8 | 31 | mention « Les performances futures ne sont pas garanties. » | Propriétaire `souscription-web` (mention réglementaire du catalogue) |
| 9 | 32 | `Cache-Control: no-store` | Gateway `SubscriptionItSuccessorTest#theFiveSecurityHeadersAreOnEveryResponseClass` ; Cucumber gateway « Entetes de securite ajoutes a chaque reponse » |
| 10 | 33 | CSP interdit tout script (directive `script-src`) | Gateway `SubscriptionItSuccessorTest#theFiveSecurityHeadersAreOnEveryResponseClass` (CSP configurée, `default-src` sans source autorisée, qui interdit tout script) |
| 11 | 34 | cookie de session `HttpOnly` | Gateway `SubscriptionItSuccessorTest#noSessionCookieAndAStrictCsrfCookie`, `NoSessionCookieTest#noRouteEmitsASessionCookie` (plus de cookie de session ; le cookie CSRF est volontairement lisible par la SPA, donc sans `HttpOnly` — `docs/csrf-trust-model.md`) |
| 12 | 35 | cookie `SameSite=Lax` | Gateway `SubscriptionItSuccessorTest#noSessionCookieAndAStrictCsrfCookie` (`SameSite=Strict` et `Secure`, durcissement validé : même origine) |
| 13 | 38 | `GET /produit.do` (paramètre `code`) → 200 pour les 3 codes | Gateway `SubscriptionItSuccessorTest#catalogueListsThreeProductsEachReadable` (`GET /api/v1/products/{code}` × 3) |
| 14 | 39 | champ `csrf` non vide | Gateway `CsrfEdgeTest#cookieTokenEchoedInHeaderIsAccepted` (jeton lu dans le cookie `XSRF-TOKEN`, non nul) ; Cucumber gateway « Un jeton anti-CSRF est cree pour la session a l'acces d'une action » |
| 15 | 40 | un formulaire `commencer.do` | Propriétaire `souscription-web` (bouton « Souscrire » de la fiche) ; opération `POST /api/v1/drafts` : Gateway `GatewayApiRoutesTest#startDraftAnswers201WithLocation` |
| 16 | 42 | produit inconnu → 404 | Gateway `SubscriptionItSuccessorTest#notFoundSurface[/api/v1/products/INCONNU]` ; Cucumber gateway « Consultation de la fiche d'un placement inconnu » |
| 17 | 43 | `/WEB-INF/jsp/recapitulatif.jsp` → 404 | Gateway `SubscriptionItSuccessorTest#notFoundSurface[/WEB-INF/jsp/recapitulatif.jsp]` |
| 18 | 44 | `/console` → 404 | Gateway `SubscriptionItSuccessorTest#notFoundSurface[/console]` |

## Test `completeJourneyEscapesUserDataAndPreventsDuplicateConfirmations`

| # | Ligne | Assertion source | Successeur |
| --- | --- | --- | --- |
| 19 | 54 | page « Simulation de votre placement » | Propriétaire `end-to-end` (« Parcours complet jusqu'au recapitulatif ») ; enchaînement API : Gateway `GatewayApiRoutesTest#putDraftSubscriberAnswers200` |
| 20 | 58 | `#capital-final` présent | Gateway `GatewayApiRoutesTest#simulateAnswers200` ; Cucumber gateway « Simulation d'un placement standard » ; affichage → `souscription-web` |
| 21 | 62 | prénom échappé `&lt;em&gt;` | Propriétaire `souscription-web` (interpolation Angular échappée) ; la gateway ne sert que du JSON sous CSP `default-src` sans source autorisée et `X-Content-Type-Options: nosniff` (`SubscriptionItSuccessorTest#theFiveSecurityHeadersAreOnEveryResponseClass`) |
| 22 | 63 | zéro élément `em` injecté | Propriétaire `souscription-web` (pas de `innerHTML` non assaini) |
| 23 | 65 | prénom réaffiché tel que saisi | Propriétaire `souscription-web` (« Les donnees deja saisies sont reaffichees a l'etape souscripteur ») ; stockage du brouillon → `souscription-service` |
| 24 | 69 | `#initial` réaffiché `1000,00` | Propriétaire `souscription-web` (« Les donnees de simulation deja saisies sont reaffichees ») |
| 25 | 70 | `#monthly` réaffiché `100,00` | Propriétaire `souscription-web` (« Les donnees de simulation deja saisies sont reaffichees ») |
| 26 | 75 | confirmation sans acceptation → 200 (refus) | Propriétaire `souscription-service` (« Conditions non acceptees ») |
| 27 | 76 | message « Vous devez valider » | Propriétaire `souscription-web` (message) / `souscription-service` (règle) |
| 28 | 79 | confirmation acceptée → 200 | Gateway `GatewayApiRoutesTest#confirmDraftAnswers201ThenReplaysWith200` |
| 29 | 81 | référence au format UUID | Gateway `GatewayApiRoutesTest#confirmDraftAnswers201ThenReplaysWith200` ; génération → `souscription-service` |
| 30 | 83 | double soumission → même référence | Gateway `IdempotencyTest#twoSubmissionsOfTheSameKeyCarryItDownstreamAndWriteOnce` ; Cucumber gateway « Double soumission de la confirmation avec la meme cle » |
| 31 | 84 | `/confirmation.do` → même référence | Propriétaire `souscription-service` (« Recherche d'une souscription enregistree par sa cle ») ; relais `GET /api/v1/subscriptions/{reference}` : Gateway `GatewayApiRoutesTest#getUnknownSubscriptionAnswers404` |
| 32 | 86 | client étranger → 302 (pas d'accès) | Propriétaire `souscription-service` (« Consultation de la confirmation sans souscription aboutie ») ; côté gateway, pas de session à usurper : `NoSessionCookieTest#aClientSuppliedSessionCookieIsNeverEchoedBack` |

## Test `validatesInputsKeepsValuesAndBlocksStaleConfirmation`

| # | Ligne | Assertion source | Successeur |
| --- | --- | --- | --- |
| 33 | 95 | saisie invalide → 200 (formulaire réaffiché) | Propriétaire `souscription-service` (« Saisie invalide du souscripteur a l'etape 2 ») ; transport du 400 problem+json : Gateway `ProblemMapperTest#validationFailureCarriesTheRejectedFields` |
| 34 | 96 | `#lastName` conservé | Propriétaire `souscription-web` (« Les valeurs saisies sont conservees telles quelles ») |
| 35 | 97 | `aria-invalid="true"` sur `#email` | Propriétaire `souscription-web` |
| 36 | 98 | `aria-describedby` → `email-error` | Propriétaire `souscription-web` |
| 37 | 99 | 3 erreurs listées | Propriétaire `souscription-service` (`validation-souscripteur`) ; champs rejetés relayés : Gateway `ProblemMapperTest#validationFailureCarriesTheRejectedFields` |
| 38 | 100 | récapitulatif avant étape → renvoi souscripteur | Propriétaire `souscription-service` (« Acces au recapitulatif avant la simulation ») |
| 39 | 106 | 3 erreurs de montants | Propriétaire `souscription-simulation-service` (« Bornes du versement initial », « Bornes de la mensualite », « Bornes de la duree en annees ») ; Cucumber gateway « Parametres de simulation rejetes par le moteur de calcul » |
| 40 | 114 | clé de confirmation périmée → 409 | Gateway `SubscriptionItSuccessorTest#staleConfirmationKeyIs409WithoutInternalDetail`, `IdempotencyTest#aKeyTheDraftNoLongerCarriesIsRejectedAsAConflict` |
| 41 | 115 | réponse 409 sans « Exception » | Gateway `SubscriptionItSuccessorTest#staleConfirmationKeyIs409WithoutInternalDetail` |

## Test `rejectsForgedPostsAndExplainsMissingSession`

| # | Ligne | Assertion source | Successeur |
| --- | --- | --- | --- |
| 42 | 122 | POST sans jeton → 403 | Gateway `CsrfEdgeTest#missingTokenIsRejected` (+ `gateway.rejections{403,csrf-missing}` et 1 entrée de journal) ; Cucumber gateway « Soumission refusee en l'absence de jeton anti-CSRF » |
| 43 | 123 | POST avec jeton forgé → 403 | Gateway `CsrfEdgeTest#forgedTokenIsRejected`, `CsrfEdgeTest#forgedMatchingPairIsRejected` (+ `{403,csrf-mismatch}` et 1 entrée) ; Cucumber gateway « Soumission refusee en cas de jeton anti-CSRF incorrect » |
| 44 | 124 | jeton valide, code `BAD` → 400 | Gateway `CsrfEdgeTest#validTokenWithInvalidProductCodeIs400` ; Cucumber gateway « Code produit vide ou mal formate sur la fiche produit » |
| 45 | 125 | `GET /commencer.do` → 405 | Gateway `SubscriptionItSuccessorTest#undeclaredMethodOnARouteIs405WithAllow` (`GET /api/v1/drafts` → 405, `Allow: POST`) et `#forbiddenMethodIs405WithAllow` (DELETE/PATCH/OPTIONS/TRACE → 405 avec `Allow`, + `{405,method}` et 1 entrée) ; Cucumber gateway « Methodes HTTP non autorisees », « Methode HTTP non conforme a l'etape du parcours » |
| 46 | 128 | session expirée → 302 | Gateway `CsrfEdgeTest#expiredTokenIsReplacedOnTheNextSafeRequest` (l'expiration est portée par le jeton signé, plan 15 : une requête sûre reçoit un nouveau cookie) ; reprise sans brouillon → `souscription-service` (« Reprise du parcours sans brouillon en session ») |
| 47 | 129 | message « votre session a expiré » | Propriétaire `souscription-web` (message affiché sur 403/404 du brouillon) |
| 48 | 130 | confirmation avec jeton d'une session expirée → 403 | Gateway `CsrfEdgeTest#expiredTokenIsRejected` (jeton correctement signé mais plus vieux que `gateway.csrf.ttl`, + `{403,csrf-mismatch}` et 1 entrée) |

## Assistants (`Client`, `Page`)

| # | Ligne | Assertion source | Successeur |
| --- | --- | --- | --- |
| 49 | 149 | toute redirection suivie est un 302 | Sans objet en gateway (API JSON sans redirection) : Gateway `GatewayApiRoutesTest#startDraftAnswers201WithLocation` (201 + `Location`) ; navigation → `souscription-web` |
| 50 | 203 | zéro notice de démonstration dans une page | Propriétaire `souscription-web` |
| 51 | 205 | pas de texte « Application Struts 1 / JSP » | Propriétaire `souscription-web` ; côté gateway, pas de réponse d'erreur qui divulgue de détail interne : `SubscriptionItSuccessorTest#downstreamFailureReturnsTheDocumentedPayload` |
| 52 | 206 | zéro `.tabs-note` | Propriétaire `souscription-web` |

## Ajouts sans assertion source directe

| Successeur gateway | Couverture |
| --- | --- |
| `SubscriptionItSuccessorTest#downstreamFailureReturnsTheDocumentedPayload` | 5xx amont → 502 problem+json documenté (`docs/error-mapping.md`), sans texte amont |
| `SubscriptionItSuccessorTest#downstreamUnavailableReturnsTheDocumentedPayload` | 503 amont → 503 problem+json avec `Retry-After` |
| `SubscriptionItSuccessorTest#theFiveSecurityHeadersAreOnEveryResponseClass` | 5 en-têtes sur 200, 403, 405, 404 routeur et 502 |

## Bilan

- Assertions recensées : **52** (dont 3 dans l'assistant `Page`, 1 dans `Client`).
- Assertions sans successeur nommé : **0**.
- jsoup : retiré du module gateway (pas de dépendance, pas d'import `org.jsoup` sous
  `souscription-gateway/src`). Le `pom.xml` racine et `SubscriptionIT` gardent le leur.
