# CONFIG_MAPPING — descripteurs XML source vers configuration cible

Chaque réglage présent dans `src/main/webapp/WEB-INF/web.xml`,
`src/main/webapp/WEB-INF/struts-config.xml`, `src/main/webapp/META-INF/context.xml` et
`src/main/tomcat/server.xml` apparaît dans exactement une ligne ci-dessous, avec une seule
destination : `application.properties`, `souscription-gateway`, `souscription-web` ou `removed`.

Les fichiers source ne sont ni modifiés ni supprimés : ils restent la référence de rollback.

| Source file | Setting | Destination | Target key or rationale |
| --- | --- | --- | --- |
| web.xml | `<web-app version="5.0" metadata-complete="true">` | removed | Descripteur de déploiement servlet supprimé : plus de scan d'annotations servlet, le modèle de composants est CDI/JAX-RS |
| web.xml | `<display-name>Financière Horizon - Souscription</display-name>` | application.properties | `quarkus.application.name=souscription-service` |
| web.xml | `<filter>` / `<filter-class>fr.demo.souscription.web.SecurityFilter</filter-class>` | souscription-gateway | Chaîne de filtrage sécurité portée par la gateway (vague 7) |
| web.xml | `<filter-mapping>` `/*` | souscription-gateway | Périmètre d'application du filtre sécurité sur la gateway (vague 7) |
| web.xml | `<listener>fr.demo.souscription.web.Application</listener>` | removed | Écouteur de contexte servlet non porté : CDI + cycle de vie Quarkus (gaps TG-009, IG-003, FG-029) |
| web.xml | `<servlet>action` = `org.apache.struts.action.ActionServlet` | removed | Struts remplacé par des ressources JAX-RS ; plus de servlet de dispatch |
| web.xml | `<init-param>config=/WEB-INF/struts-config.xml` | removed | Plus de descripteur Struts à charger |
| web.xml | `<load-on-startup>1</load-on-startup>` | removed | Démarrage géré par le runtime Quarkus |
| web.xml | `<servlet-mapping>` `*.do` | removed | Mapping `*.do` supprimé ; API REST versionnée (`/v1`) |
| web.xml | `<session-config>` (bloc) | removed | API REST sans état : aucune session HTTP côté service |
| web.xml | `<session-timeout>20</session-timeout>` | removed | La valeur de 20 minutes n'est **pas** obsolète : elle borne aujourd'hui la durée de vie du brouillon de souscription et devient une politique de rétention explicite (`PT20M`) dans le plan `13-souscription-service-draft-retention-purge` (gap FG-030) |
| web.xml | `<cookie-config><http-only>true</http-only></cookie-config>` | souscription-gateway | Attributs des cookies (HttpOnly) fixés par la gateway |
| web.xml | `<tracking-mode>COOKIE</tracking-mode>` | removed | Aucun suivi de session côté service |
| web.xml | `<welcome-file>catalogue.do</welcome-file>` | souscription-web | Route par défaut du routage Angular 21 (`/catalogue`) |
| web.xml | déclarations de page d'erreur (codes 400 / 403 / 404 / 405 / 409 / 500) vers la vue JSP d'erreur | application.properties | Réponses d'erreur JSON normalisées (`ExceptionMapper`) ; pas de page JSP. Pas de divulgation de détail : voir `quarkus.log.console.json` et l'absence de stacktrace exposée |
| web.xml | déclaration de page d'erreur pour `java.lang.Throwable` | application.properties | Mapper d'exception générique côté service (même traitement que ci-dessus) |
| web.xml | `<jsp-config>` `*.jsp` / `*.jspf` | souscription-web | Les vues JSP deviennent des composants Angular 21 |
| web.xml | `<page-encoding>UTF-8</page-encoding>` | application.properties | `quarkus.jib.jvm-arguments=-Dfile.encoding=UTF-8` |
| web.xml | déclaration `default-content-type` HTML (charset UTF-8) | souscription-web | Le service renvoie `application/json` ; le HTML est servi par la SPA |
| struts-config.xml | `<form-bean name="subscriptionForm" type="fr.demo.souscription.form.SubscriptionForm"/>` | removed | Liaison de formulaire Struts remplacée par des DTO JAX-RS validés (Bean Validation) |
| struts-config.xml | `<global-forwards>` `catalogue`, `produit`, `souscripteur`, `simulation`, `recapitulatif`, `confirmation` | souscription-web | Forwards JSP convertis en routes Angular 21 |
| struts-config.xml | `<action path="/catalogue">` et `<action path="/produit">` (`CatalogAction`) | souscription-web | Vues de navigation ; les données viennent de `souscription-catalogue-service` via l'API |
| struts-config.xml | `<action path="/commencer">` (`SubscriptionAction`) | application.properties | Endpoint REST de création de brouillon (`POST /v1/souscriptions`) |
| struts-config.xml | `<action path="/souscripteur">` (`SubscriptionAction`) | application.properties | Endpoint REST de mise à jour du souscripteur du brouillon |
| struts-config.xml | `<action path="/simulation">` (`SubscriptionAction`) | application.properties | Endpoint REST de simulation (appel distant à `souscription-simulation-service`) |
| struts-config.xml | `<action path="/recapitulatif">` (`SubscriptionAction`) | application.properties | Endpoint REST de lecture du brouillon |
| struts-config.xml | `<action path="/confirmer">` (`SubscriptionAction`) | application.properties | Endpoint REST de confirmation (`POST /v1/souscriptions/{id}/confirmation`) |
| struts-config.xml | `<action path="/confirmation">` (`SubscriptionAction`) | souscription-web | Écran de confirmation rendu par la SPA |
| struts-config.xml | `<message-resources parameter="ApplicationResources"/>` | application.properties | `quarkus.default-locale=fr-FR` ; messages applicatifs via bundle Quarkus |
| context.xml | `<CookieProcessor ... sameSiteCookies="lax"/>` | souscription-gateway | Politique `SameSite` des cookies (double-submit CSRF, `SameSite=Strict`) fixée par la gateway (vague 7) |
| server.xml | `<Server port="@cargo.tomcat.shutdown.port@" shutdown="SHUTDOWN">` | removed | Port d'arrêt Tomcat sans objet ; arrêt géré par le conteneur/orchestrateur |
| server.xml | `address="127.0.0.1"` (Server) | removed | Liaison réseau gérée par le déploiement conteneurisé |
| server.xml | `<Listener ...JreMemoryLeakPreventionListener/>` | removed | Spécifique au conteneur Tomcat ; sans objet sur Quarkus |
| server.xml | `<Service name="Catalina">` / `<Engine>` / `<Host>` | removed | Hiérarchie de conteneur Tomcat sans équivalent cible |
| server.xml | `<Connector port="@cargo.servlet.port@">` | application.properties | `quarkus.http.port=8080` |
| server.xml | `<Connector address="127.0.0.1">` | removed | Exposition réseau gérée par le service Kubernetes ; services backend non joignables de l'extérieur (mTLS) |
| server.xml | `protocol="HTTP/1.1"` | application.properties | Protocole HTTP natif du serveur Vert.x de Quarkus (aucune clé requise) |
| server.xml | `connectionTimeout="20000"` | application.properties | Valeurs par défaut Quarkus retenues ; objectif de disponibilité 99,9 % |
| server.xml | `URIEncoding="UTF-8"` | application.properties | `quarkus.jib.jvm-arguments=-Dfile.encoding=UTF-8` |
| server.xml | `maxPostSize="16384"` | application.properties | `quarkus.http.limits.max-body-size=16K` |
| server.xml | `server="Horizon"` | removed | Quarkus n'émet pas d'en-tête `Server` identifiant le produit |
| server.xml | `appBase="webapps"` / `unpackWARs="true"` / `autoDeploy="false"` | removed | Déploiement WAR remplacé par un exécutable conteneurisé |
| server.xml | `<Valve ErrorReportValve showReport="false" showServerInfo="false"/>` | application.properties | Aucune stacktrace ni bannière serveur exposée ; erreurs JSON normalisées |
| `fr.demo.souscription.web.Application` (destruction du contexte web) | desinscription manuelle du pilote JDBC + diagnostic `"Libération du pilote JDBC impossible ; SQLState=…"` | dropped — propriétaire : Agroal / cycle de vie Quarkus (FG-029) | La désinscription manuelle du pilote et son diagnostic ne sont pas portés : Agroal possède la fermeture du pool au `@Shutdown`. Aucun site d'émission cible ; propriétaire nommé = extension `quarkus-agroal` (datasource livré au plan 07) |
| `fr.demo.souscription.web.Application` (initialisation du contexte web) | diagnostic `"Initialisation de la base de données impossible (…)"` | souscription-service — plan 25 | Observateur de démarrage + sonde de readiness (`25-souscription-service-health-probes`) |
| `SubscriptionAction` (branche `SQLException`) | `"Enregistrement refusé ; SQLState=" + ex.getSQLState()` | souscription-service — plans 17 / 23 | `LOG.warnf("Enregistrement refuse ; sqlState=%s", …)` dans le chemin de confirmation ; en attente, consigné dans `LoggingConventions` |

## Scénarios Gherkin sans objet

Le bootstrap filesystem (propriété système de chemin de données) et la désinscription manuelle du pilote JDBC ne sont pas
portés (initialisation / destruction du contexte web de `fr.demo.souscription.web.Application`).
Agroal et le cycle de vie Quarkus assurent la gestion du datasource. En conséquence, les cinq
scénarios suivants de `features/initialisation-application/initialisation-application.feature`
deviennent sans objet :

- `Les permissions du repertoire de donnees sont restreintes`
- `Le chemin de la base est lu depuis la propriete systeme`
- `Le systeme de fichiers ne supporte pas les permissions POSIX`
- `Echec d'initialisation de l'application`
- `Echec de liberation d'un pilote JDBC a l'arret`

Aucune clé `quarkus.datasource.*`, aucune URL JDBC et aucun secret n'est introduit dans ce plan ;
le datasource est traité au plan 07.
