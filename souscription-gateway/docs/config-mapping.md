# Correspondance descripteurs de déploiement → configuration Quarkus

Référence : plan 03 (W0-T05). Les descripteurs source (`src/main/webapp/WEB-INF/web.xml`,
`src/main/webapp/META-INF/context.xml`, descripteur Tomcat sous `src/main/tomcat/`) sont conservés en
lecture seule comme référence comportementale et ne sont jamais modifiés.

## Descripteur serveur Tomcat

| Entrée source | Valeur source | Clé cible / verdict |
| --- | --- | --- |
| `Server@port` (shutdown) | `@cargo.tomcat.shutdown.port@` | **abandonné, pas d'équivalent** — Quarkus n'a pas de port d'arrêt |
| `Listener JreMemoryLeakPreventionListener` | — | **abandonné, pas d'équivalent** — spécifique au conteneur Tomcat |
| `Connector@port` (HTTP) | `@cargo.servlet.port@` | `quarkus.http.port=8080` (`%dev` = `18080`) |
| `Connector@address` | `127.0.0.1` | `quarkus.http.host` (par défaut `0.0.0.0` en conteneur ; exposition restreinte par l'ingress) |
| `Connector@protocol` | `HTTP/1.1` | implicite (Vert.x HTTP) |
| `Connector@connectionTimeout` | `20000` | `quarkus.http.idle-timeout` (défaut retenu) |
| `Connector@URIEncoding` | `UTF-8` | `quarkus.http.body.default-charset=UTF-8` |
| `Connector@maxPostSize` | `16384` | `quarkus.http.limits.max-body-size` (défaut Quarkus retenu) |
| `Connector@server` | `Horizon` | **abandonné** — Quarkus ne publie pas d'en-tête `Server` |
| `Engine`/`Host` (`appBase`, `unpackWARs`, `autoDeploy`) | — | **abandonné, pas d'équivalent** — plus de déploiement de WAR |
| `Valve ErrorReportValve` (`showReport=false`, `showServerInfo=false`) | — | **reporté au plan 12** — gestion unifiée des erreurs REST |

## context.xml

> Note encodage : `quarkus.http.body.default-charset=UTF-8` est déclaré explicitement comme
> successeur de `Connector@URIEncoding` et de `request.setCharacterEncoding("UTF-8")` de
> `SecurityFilter`. Quarkus 3.20 n'expose pas cette clé (avertissement « unrecognized
> configuration key ») : UTF-8 y est déjà le comportement par défaut de Vert.x/Jackson. La clé est
> conservée comme déclaration d'intention de parité et devra être revue si une clé officielle
> apparaît.

| Entrée source | Valeur source | Clé cible / verdict |
| --- | --- | --- |
| `CookieProcessor@className` (`Rfc6265CookieProcessor`) | — | implicite — les cookies Vert.x sont RFC 6265 |
| `CookieProcessor@sameSiteCookies` | `lax` | cookie CSRF double-submit posé par la passerelle en `SameSite=Strict` (même origine SPA/passerelle) ; nom via `gateway.csrf.cookie-name` |

## web.xml

| Entrée source | Valeur source | Clé cible / verdict |
| --- | --- | --- |
| `display-name` | `Financière Horizon - Souscription` | `quarkus.application.name` (nom du module Maven) |
| `filter` / `filter-mapping` `security` → `fr.demo.souscription.web.SecurityFilter` | `/*` | remplacé par un filtre Vert.x HTTP configuré par `gateway.security.csp` et `gateway.security.allowed-methods` |
| `listener` → `fr.demo.souscription.web.Application` | — | remplacé par `GatewayStartup` (`@Observes StartupEvent`) ; les volets données (H2, `schema.sql`, permissions POSIX) ne sont pas portés (`ownedData: []`) |
| `servlet` / `servlet-mapping` `action` → `ActionServlet` (`*.do`) | `/WEB-INF/struts-config.xml`, `load-on-startup=1` | **abandonné, pas d'équivalent** — Struts remplacé par des ressources JAX-RS (`/api/v1`) |
| `session-config/session-timeout` | `20` (minutes) | **pas de session serveur** — le brouillon de souscription est persisté en table dédiée (voir plans de données) ; la valeur 20 min sert de référence de durée de vie |
| `session-config/cookie-config/http-only` | `true` | cookie CSRF `HttpOnly=false` par conception double-submit ; autres cookies `HttpOnly` par défaut Quarkus |
| `session-config/tracking-mode` | `COOKIE` | **abandonné** — API sans session |
| `welcome-file-list` | `catalogue.do` | **abandonné, pas d'équivalent** (FG-006) — routage assuré par la SPA |
| `error-page` 400 → `erreur.jsp` | — | **reporté au plan 12** |
| `error-page` 403 → `erreur.jsp` | — | **reporté au plan 12** |
| `error-page` 404 → `erreur.jsp` | — | **reporté au plan 12** |
| `error-page` 405 → `erreur.jsp` | — | **reporté au plan 12** |
| `error-page` 409 → `erreur.jsp` | — | **reporté au plan 12** |
| `error-page` 500 → `erreur.jsp` | — | **reporté au plan 12** |
| `error-page` `java.lang.Throwable` → `erreur.jsp` | — | **reporté au plan 12** |
| `jsp-config/jsp-property-group` (`*.jsp`, `*.jspf`, `page-encoding`, `default-content-type`) | UTF-8 / `text/html` | **abandonné, pas d'équivalent** (FG-006) — plus de JSP ; encodage couvert par `quarkus.http.body.default-charset` |

## Propriété système Cargo

| Entrée source | Valeur source | Clé cible / verdict |
| --- | --- | --- |
| `horizon.data` (répertoire base H2) | `data/souscriptions` | **abandonné, pas d'équivalent** — la passerelle ne possède aucune donnée ; la persistance appartient à `souscription-service` |

## Supervision

| Besoin | Clé cible |
| --- | --- |
| Port dédié santé / métriques | `quarkus.management.enabled=true`, `quarkus.management.port=9000` |
