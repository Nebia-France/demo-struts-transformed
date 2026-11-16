# Concerns dropped from the gateway module — named owners

Plan 01 creates `souscription-gateway/` without any part of the legacy stack. Nothing is deleted
from the legacy WAR: every dropped concern is relocated and must have a receiving owner. This table
is the ownership record required by
`01-souscription-gateway-quarkus-module-baseline-parity` step 4. Plans 03 and 05 extend it with the
configuration and routing matrices.

| Dropped concern | Legacy location | Receiving owner | Resolved by |
| --- | --- | --- | --- |
| Struts 1 front controller (`ActionServlet`, `struts-config.xml`, 8 `*.do` mappings) | `src/main/webapp/WEB-INF/` | route surface → `souscription-gateway` `/api/v1`; navigation → `souscription-web` | plans 05, 07 |
| Struts `<global-forward>` JSP targets, JSP/JSTL view layer, `erreur.jsp` | `src/main/webapp/` | `souscription-web` (Angular SPA); error documents → RFC 7807 `problem+json` in the gateway | plans 05, 12 |
| `struts-core` dependency, JSTL tag libraries, `<jsp-config>` | root `pom.xml`, `web.xml` | `souscription-web` — no equivalent in the gateway module | plan 01 (absence is the criterion) |
| `ApplicationResources.properties` message keys, `application.css` | `src/main/resources`, `src/main/webapp` | `souscription-web` (i18n and styling) | plan 05 |
| Servlet container: Tomcat 10 WAR deploy, descripteur serveur Tomcat, `META-INF/context.xml`, plugin Cargo Maven 3 | root `pom.xml`, `src/main/tomcat` | the Quarkus fast-jar UBI9 container image of `souscription-gateway` (no servlet container, no Cargo, no WAR) | plan 04 |
| `jakarta.servlet-api`, `SecurityFilter` servlet filter | `src/main/java/.../web/SecurityFilter.java` | `souscription-gateway` Quarkus filters (headers, method allow-list, CSRF, UTF-8) | plans 11, 13, 15, 16 |
| `HttpSession` (20 min, `HttpOnly`, cookie tracking) | `web.xml`, `context.xml` | no owner — removed; succeeded by the 20-minute CSRF token TTL | plans 14, 15 |
| `slf4j-simple` stdout logging | root `pom.xml` | Quarkus JBoss LogManager / async JSON logging in the gateway | plans 02, 27 |
| `org.jsoup` HTML assertions (`SubscriptionIT`) | root `pom.xml` (test scope) | stays in the legacy tier; HTML-rendering assertions → `souscription-web`; gateway-owned assertions ported to REST Assured JSON tests (per-assertion successors in `docs/assertion-successors.md`) | plan 31 |
| Java 8 enforcer (`requireJavaVersion [1.8,1.9)`) | root `pom.xml` | not inherited — the module targets Java 21 (`maven.compiler.release=21`) | plan 01 |
| Business services (`CatalogService`, `SimulationService`, `SubscriptionService`), H2 datastore, `schema.sql`, `Application.java` bootstrap | `src/main/java/fr/demo/souscription/{service,repository,web}` | `souscription-service`, `souscription-simulation-service`, `souscription-catalogue-service` — reached over HTTP/mTLS, never in-process | plans 17-24 |
| `fr_FR` display formatting (`dd/MM/yyyy`, amount rendering) | JSP views | `souscription-web`; the wire format is ISO-8601 + JSON strings | plan 08 |

The legacy tier keeps all of these declarations (`struts-core`, `jsoup`, `slf4j-simple`,
plugin Cargo Maven 3, `requireJavaVersion`) in the root `pom.xml`: it is the rollback boundary of
the 33-plan sequence and is never edited.
