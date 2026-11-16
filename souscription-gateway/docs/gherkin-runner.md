# Gherkin runner contract — souscription-gateway

The 9 `.feature` files (88 scenarios) live at the repository root under `features/`. The gateway
module hosts the JUnit Platform suite that executes them with Cucumber-JVM.

## Components

| Element | Path |
| --- | --- |
| Suite class | `src/test/java/fr/demo/souscription/gateway/GatewayCucumberTest.java` |
| Glue package | `src/test/java/fr/demo/souscription/gateway/steps/` |
| Glue base class | `.../steps/GatewayStepsSupport.java` |
| Engine configuration | `src/test/resources/junit-platform.properties` |

The suite selects `../features` (relative to the module directory) and restricts the glue to
`fr.demo.souscription.gateway.steps`. The generated stubs under `features/step_definitions/` are
**not** on the test classpath and must not be modified or deleted — the gateway glue classes are
added progressively by plans 07 to 33 and consolidated in plan 31.

## Application under test

The suite runs under failsafe in the `verify` phase, i.e. after the fast-jar has been produced. The
`Given l'application Horizon est demarree` step starts `target/quarkus-app/quarkus-run.jar` once per
JVM on `gateway.cucumber.port` (default 18581; the `@QuarkusTest` suite itself binds random ports) (`.../steps/GatewayUnderTest.java`, log in
`target/gateway-cucumber-app.log`) and a shutdown hook stops it. The packaged application is started with `-Dquarkus.profile=cucumber`: the
gate talks to plain-HTTP WireMock doubles, while the `%prod` configuration mounts the outbound mTLS
keystore and truststore from a cluster secret (`docs/mtls.md`) that no build agent has — under the
`prod` profile every downstream call fails with a 500 before any request leaves the process. The glue therefore asserts on real
HTTP responses, headers included.

## Reference commands

```bash
mvn -f souscription-gateway/pom.xml verify -Pcucumber
mvn -f souscription-gateway/pom.xml verify -Pcucumber -Dcucumber.features=../features/securite-web
mvn -f souscription-gateway/pom.xml verify -Pcucumber -Dcucumber.filter.name="Methode HTTP non autorisee sur le catalogue"
```

`-Dcucumber.filter.name` values may contain an apostrophe
(`-Dcucumber.filter.name="Indisponibilite de l'enregistrement"`). Quarkus re-parses the Maven
command line (`MAVEN_CMD_LINE_ARGS`) and would reject it as unbalanced quotes, so the `cucumber`
profile skips the no-op `generate-code` mojo and passes only `-Pcucumber` as `MAVEN_CMD_LINE_ARGS`
to the surefire and failsafe forks.

The default build (`mvn -f souscription-gateway/pom.xml clean test`) does **not** run the suite:
surefire excludes `GatewayCucumberTest` and the failsafe execution is bound only to the `cucumber`
profile. This keeps intermediate plans green while the step definitions are still skeletons.

## Warning — scenario titles

Scenario titles in the `.feature` files are written in French **without diacritics**
(`Methode HTTP non autorisee...`, not `Méthode HTTP non autorisée...`). They must be reproduced
exactly: any re-accentuation breaks `-Dcucumber.filter.name`.

## Scenario ownership

Each of the 88 scenarios has **exactly one** owner and one executing suite. The versioned matrix is
`features/SCENARIO_OWNERSHIP.json`, and `features/SCENARIO_OWNERSHIP.md` is its readable view
(W7-T01):

| Owner | Scenarios |
| --- | --- |
| `souscription-gateway` | 24 |
| `souscription-service` | 41 |
| `souscription-simulation-service` | 8 |
| `souscription-web` | 6 |
| `end-to-end` | 5 |
| `souscription-catalogue-service` | 4 |

Every scenario carries its owner as a tag, `@<owner>`. The tag sits on the scenario, or on the
feature when the whole feature has one owner: `securite-web`, `validation-souscripteur`,
`persistance-souscriptions` and `formulaire-souscription`. `GatewayCucumberTest` declares
`cucumber.filter.tags=@souscription-gateway`, so the gate runs the 24 gateway-owned scenarios
(50 executions once the `Examples` rows are expanded). It never runs a sibling or end-to-end
scenario. A scenario without an owner tag is not run anywhere, and the ownership check reports it.

`src/test/scripts/check-scenario-ownership.sh` (pipeline stage `gherkin-allocation`) fails when:

- the total is not 88;
- a scenario is orphaned or allocated twice;
- a matrix entry is stale;
- the gateway does not own exactly 24 scenarios;
- an owner tag contradicts the matrix;
- the `.md` view differs from the `.json` matrix.

Once a scenario changes owner, update the JSON, the `.md` view and the tag together.

There is no two-faced scenario any more. « Indisponibilite de l'enregistrement » is owned by the
gateway only, which produces the public 503 with `Retry-After`. The storage outage behind it is
covered by the `souscription-service` scenarios of `persistance-souscriptions`.
