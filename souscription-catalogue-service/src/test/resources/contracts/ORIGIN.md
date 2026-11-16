# Origin of the reference contracts copied here

These files are verbatim copies, taken for `InterApplicationContractTest` (plan 37) so the test is
runnable from the module directory alone. The authoritative originals live outside this module:

| Copy | Origin |
| --- | --- |
| `souscription-gateway-to-souscription-catalogue-service/openapi.yaml` | `global/contracts/souscription-gateway-to-souscription-catalogue-service/openapi.yaml` |
| `souscription-gateway-to-souscription-catalogue-service/security-policy.json` | `global/contracts/souscription-gateway-to-souscription-catalogue-service/security-policy.json` |
| `souscription-gateway-to-souscription-catalogue-service/resilience-contract.json` | `global/contracts/souscription-gateway-to-souscription-catalogue-service/resilience-contract.json` |
| `souscription-service-to-souscription-catalogue-service/openapi.yaml` | `global/contracts/souscription-service-to-souscription-catalogue-service/openapi.yaml` |
| `souscription-service-to-souscription-catalogue-service/security-policy.json` | `global/contracts/souscription-service-to-souscription-catalogue-service/security-policy.json` |
| `souscription-service-to-souscription-catalogue-service/resilience-contract.json` | `global/contracts/souscription-service-to-souscription-catalogue-service/resilience-contract.json` |
| `shared-schemas/ProductSummary.json` | `global/contracts/shared-schemas/ProductSummary.json` |
| `shared-schemas/ProductDetail.json` | `global/contracts/shared-schemas/ProductDetail.json` |
| `shared-schemas/FieldErrors.json` | `global/contracts/shared-schemas/FieldErrors.json` |
| `INTER_APPLICATION_CONTRACTS.json` | `global/contracts/INTER_APPLICATION_CONTRACTS.json` |

`InterApplicationContractTest.lesCopiesDeReferenceNeDeriventPasDesOriginaux` fails the build if a copy drifts from its origin whenever the origin is
reachable (it is skipped when the module is built outside the monorepo).
