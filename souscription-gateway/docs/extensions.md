# Extensions Quarkus installées — souscription-gateway

Plateforme : `io.quarkus.platform:quarkus-bom:3.20.6.2` (versions gérées par le BOM).

> `mvn quarkus:list-extensions -DinstalledOnly` ne peut pas s'exécuter dans cet environnement
> (`registry.quarkus.io` est inaccessible). La liste ci-dessous est la liste résolue via
> `mvn dependency:list`.

## Extensions déclarées (W0-T03)

| Artefact | Version |
| --- | --- |
| io.quarkus:quarkus-arc | 3.20.6.2 |
| io.quarkus:quarkus-rest-jackson | 3.20.6.2 |
| io.quarkus:quarkus-rest-client-jackson | 3.20.6.2 |
| io.quarkus:quarkus-smallrye-openapi | 3.20.6.2 |
| io.quarkus:quarkus-smallrye-health | 3.20.6.2 |
| io.quarkus:quarkus-micrometer-registry-prometheus | 3.20.6.2 |
| io.quarkus:quarkus-smallrye-fault-tolerance | 3.20.6.2 |
| io.quarkus:quarkus-opentelemetry | 3.20.6.2 |
| io.quarkus:quarkus-vertx-http | 3.20.6.2 |
| io.quarkus:quarkus-hibernate-validator | 3.20.6.2 |
| io.quarkus:quarkus-config-yaml | 3.20.6.2 |
| io.quarkus:quarkus-logging-json | 3.20.6.2 |

`quarkus-logging-json` est requis pour que `quarkus.log.console.json` soit pris en compte
(sans cette extension la clé est ignorée avec un avertissement au démarrage).

## Extensions transitives notables

`quarkus-rest`, `quarkus-rest-common`, `quarkus-rest-client`, `quarkus-rest-client-jaxrs`,
`quarkus-rest-client-config`, `quarkus-jackson`, `quarkus-jsonp`, `quarkus-micrometer`,
`quarkus-smallrye-context-propagation`, `quarkus-smallrye-stork`, `quarkus-swagger-ui`,
`quarkus-tls-registry`, `quarkus-vertx`, `quarkus-virtual-threads`, `quarkus-netty`,
`quarkus-mutiny`, `quarkus-core`, `quarkus-credentials`, `quarkus-grpc-common`,
`quarkus-security-runtime-spi`, `quarkus-vertx-latebound-mdc-provider`.

## Contrôles

- Aucun artefact `quarkus-resteasy*` (pile legacy incompatible avec `quarkus-rest`).
- Aucun artefact `org.slf4j:slf4j-simple` (conflit avec le `LogManager` Quarkus).
  Le code doit utiliser `org.jboss.logging.Logger` ou l'API SLF4J fournie par Quarkus.
- OpenTelemetry est actif depuis le plan 28 (`quarkus.otel.sdk.disabled=false`, export OTLP vers
  `${OTEL_COLLECTOR_ENDPOINT}`) et reste inactif en `%test` et `%dev` ; voir
  `docs/observability.md` section 9.
