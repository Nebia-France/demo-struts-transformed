# Modele de confiance CSRF de souscription-gateway (W3-T04)

Ce document enregistre les ecarts deliberes entre la protection CSRF de la source
(`fr.demo.souscription.web.SecurityFilter` + le fragment `csrf.jspf`) et celle de la gateway
(`CsrfTokenService`, `CsrfCookieIssuer`, `CsrfDoubleSubmitFilter`). Tout ecart non liste ici est un
defaut, pas une decision.

## Ce qui est conserve a l'identique

- comparaison par `MessageDigest.isEqual` sur les octets UTF-8, jamais `String.equals` (FG-002) ;
- rejet de tout jeton soumis de plus de 100 caracteres (`gateway.csrf.max-token-length`) ;
- un jeton deja detenu n'est jamais regenere ;
- les requetes sures (`GET`, `HEAD`) ne sont jamais refusees ;
- les ressources hors du perimetre applicatif ne sont pas controlees ;
- refus = 403, sans divulgation de la cause.

## Les cinq ecarts assumes

| # | Source | Cible | Raison / consequence |
|---|--------|-------|----------------------|
| 1 | Jeton en attribut de session, inaccessible au client | Cookie `XSRF-TOKEN` **sans `HttpOnly`** | Le schema double-submit exige que la SPA lise la valeur pour la reemettre en entete. Consequence : une XSS permettrait de lire le jeton. La contre-mesure est la CSP `default-src 'none'` du plan 11, pas `HttpOnly`. |
| 2 | Cookie de session `SameSite=lax` (defaut conteneur) | `SameSite=Strict` | Renforcement. **Valide uniquement parce que la SPA et la gateway sont servies par la meme origine derriere un ingress partage (OD-004).** Toute separation d'origine (CDN dedie, sous-domaine distinct) invalide ce choix et rouvre la decision : il faudrait repasser en `SameSite=None; Secure` + CORS explicite. |
| 3 | Jeton en champ de formulaire cache (`csrf.jspf`) | Jeton dans l'entete `X-CSRF-Token` | L'API est JSON : il n'y a plus de formulaire. L'entete a un avantage propre : il n'est pas emissible par un formulaire cross-site, donc le simple fait d'exiger un entete personnalise ajoute une barriere. |
| 4 | Verification sur `POST` seulement, perimetre `path.endsWith(".do")` | Verification sur **toute methode non sure** (`POST`, `PUT`, `PATCH`, `DELETE`), perimetre `/api/v1/**` | L'API orientee ressources du plan 07 expose des methodes que la source ne connaissait pas ; aucune ne peut rester non controlee (NFG-002). Le perimetre exclut les ressources statiques et les endpoints de management `/q/health`, `/q/metrics` (non exposes publiquement, plan 25). Une methode hors de `gateway.security.allowed-methods` reste traitee en 405 par `MethodAllowListFilter` : le verdict de methode reste le seul point de decision des methodes. |
| 5 | Duree de vie liee a la session (timeout de 20 minutes) | Duree de vie portee par le jeton : horodatage signe en HMAC-SHA256, TTL `gateway.csrf.ttl` = 20 minutes, verifiee cote serveur | La gateway est sans etat : il n'y a plus de session a expirer. Le `Max-Age` du cookie n'est qu'une indication cliente et n'est jamais la base de la decision ; un jeton dont la signature ou l'horodatage ne se verifie pas est traite comme expire. |

## Ce que le modele ne protege pas

- Ce n'est pas de l'authentification : l'API reste anonyme, comme la source. Le jeton n'identifie
  personne et n'autorise rien ; il atteste seulement que la requete provient d'un contexte qui a pu
  lire le cookie.
- Pas de liaison a un utilisateur ni a un brouillon : un jeton valide est accepte sur n'importe
  quelle route changeante. L'autorisation sur le brouillon appartient a souscription-service.
- Pas de revocation : un jeton emis reste valable jusqu'a son TTL. La rotation de
  `gateway.csrf.signing-key` invalide tous les jetons en circulation.

## Contrat avec la SPA (souscription-web)

1. Au demarrage, la SPA appelle `GET /api/v1/csrf-token` ; la reponse pose le cookie `XSRF-TOKEN`
   et renvoie la meme valeur dans le corps.
2. Sur toute requete `POST` / `PUT` / `PATCH` / `DELETE` vers `/api/v1`, la SPA envoie cette valeur
   dans l'entete `X-CSRF-Token` (declare requis dans le document OpenAPI, schema de securite
   `csrfToken`).
3. Sur un 403, la SPA redemande un jeton et rejoue **une** fois ; un second 403 est une erreur
   presentee a l'utilisateur.
4. La SPA ne doit jamais stocker le jeton ailleurs que dans le cookie (pas de `localStorage`).
