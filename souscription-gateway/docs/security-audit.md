# Audit de securite de bordure — souscription-gateway (W7-T04)

Plan 33, etape 3. Audit execute le **2026-11-14** sur le build final (Netty 4.1.140.Final, Vert.x
4.5.36, Jackson 2.18.12 ; voir `docs/cve-review.md`), puis rejoue par la verification de parite 33
le meme jour (commit `9026a942`). Chaque controle est verifie par :

- les tests automatises de `mvn -f souscription-gateway/pom.xml clean verify -Pcucumber`
  (319 tests surefire, 0 echec ; 24 scenarios passerelle, 50 executions, 0 echec) ;
- une sonde manuelle, envoyee par `curl` a l'artefact package (`target/quarkus-app`, profil non-prod
  `load`, port 8098) pendant la campagne de charge de `docs/capacity.md`.

Colonne « Origine source / decision » : un controle **preserve** est rattache a son mecanisme source
(`BASELINE.md` §4 : `SecurityFilter`, `web.xml`, `context.xml`, assertions de `SubscriptionIT`).
Tout ecart delibere est marque **accepted change** et renvoie au document qui porte la decision. Un
controle sans equivalent source est marque **accepted change (new control)** avec sa decision.

| # | Controle (plan) | Statut | Test(s) de verification | Commande / sonde manuelle | Origine source / decision |
| --- | --- | --- | --- | --- | --- |
| 1 | Cinq en-tetes de securite sur chaque reponse (plan 11) | **Verifie** | `SecurityHeaderFilterTest` (6/6 : 200, 404 routeur, 404 JAX-RS, 500 force) ; `SecurityHeaderParityTest` (4/4) ; scenario « Entetes de securite ajoutes a chaque reponse » | `curl -sD - -o /dev/null :8098/api/v1/products` → `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'`, `Cache-Control: no-store` ; memes en-tetes sur un 404 | **Preserve** : `SecurityFilter` (4 en-tetes a l'identique) ; `SubscriptionIT` l. 32 (`Cache-Control: no-store`). **Accepted change** : CSP restreinte (narrowed CSP) de la politique document source a `default-src 'none'; frame-ancestors 'none'; base-uri 'none'` (la passerelle ne sert aucun HTML ; `SubscriptionIT` l. 33 `script-src 'none'` passe a l'hote statique de `souscription-web`) — decision `docs/security-headers.md` §3 |
| 2 | Double-soumission CSRF, matrice a 6 cas (plan 16) | **Verifie** | `CsrfMatrixTest#theSixRejectionCasesAllAnswer403WithAnIndistinguishableBody` (suite 5/5) ; `CsrfMatrixParityTest` (8/8) ; `CsrfEdgeTest` (7/7) ; `CsrfTokenServiceTest` (8/8) ; scenarios CSRF de `securite-web` | `POST /api/v1/drafts` sans jeton → **403** ; avec la paire cookie `XSRF-TOKEN` + en-tete `X-CSRF-Token` → accepte. Cookie `Secure; SameSite=Strict; Path=/; Max-Age=1200` | **Preserve** : `SecurityFilter` (comparaison `MessageDigest.isEqual`, borne 100 caracteres, 403 sur jeton absent/falsifie) ; `SubscriptionIT` l. 122, 123, 130. **Accepted change** : CSRF etendu a `PUT`/`PATCH`/`DELETE` sur `/api/v1/**` (source : `POST` sur `*.do`) ; cookie `SameSite=Strict` (source `SameSite=lax`, `context.xml`) ; cookie non-`HttpOnly` (lecture par la SPA) ; expiration 20 min portee par le jeton signe (source : session `web.xml`) — decisions `docs/csrf-trust-model.md` « Les cinq ecarts assumes » n° 1, 2, 4, 5, et choix utilisateur valide (double-submit, meme origine, `SameSite=Strict`) |
| 3 | Liste blanche de methodes HTTP avec `Allow` (plan 13) | **Verifie** | `MethodAllowListFilterTest` (9/9) ; `MethodAllowListParityTest` (9/9) ; `SubscriptionItSuccessorTest` (405 avec `Allow`) ; scenarios « Methodes HTTP autorisees / non autorisees » | `curl -X DELETE :8098/api/v1/products` → `405 Method Not Allowed`, `Allow: GET, HEAD, POST, PUT` | **Preserve** : `SecurityFilter` (liste `GET`/`HEAD`/`POST`, `Allow` + `sendError(405)`) ; `SubscriptionIT` l. 125. **Accepted change** : `PUT` ajoute a la liste blanche pour l'API orientee ressources — decision `docs/edge-filters.md` §2.1 « `PUT` decision » |
| 4 | Limites de taille du corps et des en-tetes (plan 10) | **Verifie** | `InputLimitsTest#bodyAboveTheLimitAnswers413` (suite 3/3) ; `InputSurfaceParityTest` (4/4) ; `MetricsParityTest` (rejet `size` compte une fois) | `POST /api/v1/simulations` avec un corps de 70 000 octets et la paire CSRF → **413**. Limites `max-body-size=64K` et `max-header-size=8K` | **Preserve** : `SecurityFilter` / `web.xml` (encodage UTF-8 force, page d'erreur 400 non bavarde) ; `SubscriptionIT` l. 124 (400 sur `code=BAD`). **Accepted change (new control)** : bornes explicites 64K/8K, la source s'appuyait sur les defauts du connecteur Tomcat — decision `docs/edge-filters.md` §4 |
| 5 | Aucun cookie de session emis (plan 14) | **Verifie** | `NoSessionCookieTest#noRouteEmitsASessionCookie` (suite 3/3) ; `StatelessParityTest` (3/3) ; `no-affinity-check.sh` | Seul `XSRF-TOKEN` (jeton signe, sans etat serveur) est pose : aucun `JSESSIONID` ni autre cookie de session. Un parcours de 10 appels alternes sur 2 repliques sans affinite reussit a 100 % (`docs/capacity.md`) | **Origine** : `web.xml` `<session-config>` (20 min, `HttpOnly`, suivi `COOKIE`), `context.xml` `SameSite=lax` ; `SubscriptionIT` l. 34, 35, 128, 129. **Accepted change** : pas de cookie de session ; la borne de 20 min est reprise par le TTL du jeton CSRF (assertion successeur : jeton expire → 403) — decision `docs/stateless-model.md` §1 et §5 |
| 6 | Les trois services aval injoignables hors du cluster (plan 19) | **Verifie (declaratif + mTLS)** ; sondes en cluster **deferred, flagged for human review** (voir « Deferrals ») | `bash souscription-gateway/src/test/scripts/network-isolation-check.sh` (refus par defaut sur les 3 aval, seule `app=souscription-gateway` autorisee sur TCP 8080, aucune `Ingress`/`LoadBalancer`/`NodePort`, ingress local et compose sans aval publie) → `OK` ; `MtlsTest` (4/4 : `callWithoutClientCertificateIsRefused`, `mismatchedServerHostFailsWhileHostVerificationIsOn`, `hostVerificationIsEnabledOnTheThreeClientsAndNeverDisabled`) ; `ChaosIT` (certificat serveur invalide → 503) | Sondes en cluster (etapes 1 a 4 de `docs/network-isolation.md`) : a executer par l'equipe infrastructure, proprietaire des `NetworkPolicy`. Ce poste n'a pas acces au cluster ; aucune sortie de ces sondes n'est archivee dans ce depot | **Origine** : appels de service en processus (`CatalogService`, `SimulationService`, `SubscriptionService` publies dans le `ServletContext`), 0 arete reseau. **Accepted change (new control)** : 3 aretes distantes mTLS, aval inaccessibles hors cluster — decision `docs/mtls.md`, `docs/network-isolation.md`, choix utilisateur valide (mTLS, aval injoignables de l'exterieur) |
| 7 | Aucune donnee personnelle dans les journaux, metriques et traces (plans 26 a 28) | **Verifie** | `LogHygieneTest` (5/5 : 0 jeton, 0 donnee personnelle, 1 entree par rejet) ; `MetricsParityTest` (13/13 : URI gabarisees, ni UUID, ni code produit, ni jeton dans le scrape) ; `MetricsTest` (7/7) ; `TracingTest` (6/6 : 0 attribut personnel, liste blanche d'attributs) | Apres 2,8 M requetes (puis 3,07 M au rejeu de parite), `/q/metrics` ne contient que des URI gabarisees (`/api/v1/drafts/{id}`, `/api/v1/products/{code}`). Les entrees `ERROR` du journal ne portent que le statut et la classe de cause (`request failure status=500 cause=ContextNotActiveException`) | **Preserve** : `SubscriptionIT` l. 115 (aucune `Exception` dans le corps d'erreur), `web.xml` `<error-page>` non bavardes. **Accepted change (new control)** : la source journalisait en texte libre `slf4j-simple` sans metriques ni traces ; journaux JSON, metriques et traces sans donnee personnelle — decision `docs/observability.md` §8 et §9 |

**Resultat : 7/7 controles verifies** par leurs tests automatises. La partie en cluster du controle 6
reste une deferral explicite, ci-dessous.

## Deferrals (flagged for human review)

Ces criteres ne peuvent pas s'executer sur ce poste. Ils ne sont **pas** comptes comme passes.

- **Sondes d'isolation en cluster (controle 6, plan 19)** — owner : equipe infrastructure /
  plateforme (`owner: infrastructure-team`, `docs/network-isolation.md`). Action : executer les
  etapes 1 a 4 de `docs/network-isolation.md` dans le cluster cible et archiver date, operateur et
  sortie. Echeance proposee : avant la mise en production, au plus tard le **2026-12-15**.
- **Mise a jour NVD en direct de la porte `dependency-scan`** — owner : equipe plateforme CI.
  Action : fournir la variable masquee `NVD_API_KEY`. Dernier scan reussi : 2026-11-14 sur le cache
  NVD local (donnees au 2026-10-12), recoupe OSV le 2026-11-14 (voir `docs/cve-review.md`).
- **Porte `image-scan` (trivy)** — owner : equipe plateforme CI. Le pipeline l'execute ; elle n'a pas
  pu etre rejouee localement (base trivy inaccessible derriere l'inspection TLS du reseau).

## Observations

- `/q/metrics` repond **404** sur le port public 8080 (8098 pendant la campagne). Il n'est servi que
  sur le port de management (`MetricsParityTest`, sonde manuelle).
- La montee Netty/Vert.x de la revue CVE ne modifie aucun controle : la suite complete et les
  sondes manuelles sont executees apres la montee.
- L'image se construit (`docker build -f src/main/docker/Dockerfile.jvm` OK), mais le telechargement
  de la base trivy echoue : l'inspection TLS du reseau d'entreprise n'est pas reconnue par le
  conteneur trivy. Cette porte reste executee par le pipeline.
