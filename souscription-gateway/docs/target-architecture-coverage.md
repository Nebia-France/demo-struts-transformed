# Couverture de l'architecture cible — souscription-gateway (W7-T04)

Plan 33, etape 6. Ce document associe chacun des **15 composants cibles** de
`apps/souscription-gateway/TARGET_ARCHITECTURE_MODEL.json` (cle `components`) au plan qui l'a
realise et au test qui le verifie. Tous les tests cites passent sur le build final :
`mvn -f souscription-gateway/pom.xml clean verify -Pcucumber` donne 319 tests surefire et
0 echec, `ChaosIT` donne 13/13.

| # | Composant cible (`id`) | Couche | Plan(s) de realisation | Test(s) de verification |
| --- | --- | --- | --- | --- |
| 1 | `publicApiResources`, ressources REST publiques `/api/v1` | edge-api | 07 (W1-T01) | `GatewayApiRoutesTest` (12/12), `RouteSurfaceParityTest` |
| 2 | `apiDtos`, DTO publics (ProductSummary, DraftView, SimulationResult, FieldErrors) | edge-api | 08 (W1-T02) | `WireFormatTest` (10/10), `WireFormatParityTest` |
| 3 | `openApiContract`, document OpenAPI `/api/v1` | edge-api | 09 (W1-T03), 32 (W7-T03) | `OpenApiContractTest` (6/6), `ProvidedContractTest` (10/10) |
| 4 | `securityHeaderFilter`, en-tetes de reponse de securite | edge-security | 11 (W2-T01) | `SecurityHeaderFilterTest` (6/6), `SecurityHeaderParityTest` (4/4) |
| 5 | `methodAllowListFilter`, liste blanche de methodes et encodage UTF-8 | edge-security | 13 (W2-T02), 10 (limites d'entree), 03/08 (UTF-8) | `MethodAllowListFilterTest` (9/9), `MethodAllowListParityTest` (9/9), scenario « L'encodage des requetes et des reponses est UTF-8 » |
| 6 | `csrfDoubleSubmitFilter`, CSRF sans etat par double-soumission | edge-security | 15 (W3-T02), 16 (W3-T03/T04) | `CsrfMatrixTest` (5/5), `CsrfMatrixParityTest` (8/8), `CsrfTokenServiceTest` (8/8), `CsrfEdgeTest` (7/7) |
| 7 | `problemExceptionMappers`, normalisation des erreurs RFC 7807 | edge-api | 12 (W2-T03), 20 (W4-T05) | `ProblemMapperTest` (21/21), `ProblemJsonParityTest`, `DownstreamErrorMappingTest` (6/6), `ContractErrorMappingTest` |
| 8 | `requestOrchestration`, orchestration reactive et fan-out vers les 3 services | orchestration | 07 (W1-T01), 22 (W5-T02), 23 (W5-T03) | `FanOutTest` (2/2), `FanOutParityTest`, `ExecutionModelTest` (2/2) |
| 9 | `catalogueRestClient`, client REST catalogue | outbound-integration | 17 (W4-T01), 21 (W5-T01) | `CatalogueClientTest` (3/3), `ConsumedContractTest`, `FaultTolerancePolicyTest` |
| 10 | `simulationRestClient`, client REST simulation | outbound-integration | 17 (W4-T01), 21 (W5-T01) | `SimulationClientTest` (1/1), `ConsumedContractTest`, `FaultTolerancePolicyParityTest` |
| 11 | `subscriptionRestClient`, client REST souscription (brouillons, confirmation, sans retry) | outbound-integration | 17 (W4-T01), 18 (W4-T02) | `SubscriptionClientTest` (7/7), `IdempotencyTest` (4/4), `IdempotencyParityTest` |
| 12 | `mtlsTrustMaterial`, materiel de confiance mTLS et pools de connexions | outbound-integration | 19 (W4-T03/T04) | `MtlsTest` (4/4), `CertificateExpiryMetricsTest`, `network-isolation-check.sh` |
| 13 | `resiliencePolicies`, delais, retries, disjoncteurs, bulkheads, replis | orchestration | 21 (W5-T01), 23 (W5-T03), 24 (W5-T04) | `FaultTolerancePolicyTest` (4/4), `FaultTolerancePolicyParityTest` (7/7), `ChaosIT` (13/13) |
| 14 | `runtimeConfiguration`, profils MicroProfile Config, cablage CDI, demarrage | platform | 01 (W0-T01/T02), 02 (W0-T03), 03 (W0-T04/T05), 04 (W0-T06) | `GatewayStartupTest` (1/1), scenarios « Demarrage nominal de l'application » et « Arret de l'application » |
| 15 | `observability`, sondes Health, metriques Micrometer, journaux JSON, racine de trace OTel | platform | 25 (W6-T01), 26 (W6-T02), 27 (W6-T03), 28 (W6-T04) | `HealthCheckTest` (5/5), `HealthProbeParityTest`, `MetricsTest` (7/7), `MetricsParityTest` (13/13), `LogHygieneTest` (5/5), `TracingTest` (6/6) |

**Resultat : 15/15 composants realises, chacun verifie par au moins un test en succes sur le build
final.**

Elements transverses verifies dans ce plan : allocation Gherkin 88/88, avec 24 scenarios
passerelle executes et passants (`check-scenario-ownership.sh`, `GatewayCucumberTest` sous
`-Pcucumber`). La revue CVE (`docs/cve-review.md`), l'audit de bordure (`docs/security-audit.md`)
et la capacite avec le SLI (`docs/capacity.md`) sont documentes a part.
