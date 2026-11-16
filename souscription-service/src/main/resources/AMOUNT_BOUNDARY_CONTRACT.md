# Contrat de frontiere des montants (W4-T03)

Reference partagee entre `souscription-service` et `souscription-simulation-service`. Les deux
services valident les memes bornes de maniere **volontairement dupliquee** : ils doivent rendre un
verdict identique sur chaque ligne ci-dessous. Ce tableau reflete
`global/contracts/shared-schemas/SimulationInput.json` et doit rester egal aux contraintes `CHECK`
de `src/main/resources/db/migration/V1__subscriptions.sql`
(`initial_amount BETWEEN 100 AND 100000`, `monthly_amount BETWEEN 0 AND 2000`,
`duration_years BETWEEN 1 AND 30`).

| `initialAmount` | `monthlyAmount` | `durationYears` | verdict |
| --- | --- | --- | --- |
| `100.00` | `0.00` | `1` | accepte |
| `99.99` | `100.00` | `5` | refuse (`initialAmount`) |
| `100000.00` | `2000.00` | `30` | accepte |
| `100000.01` | `100.00` | `5` | refuse (`initialAmount`) |
| `1000.00` | `2000.01` | `5` | refuse (`monthlyAmount`) |
| `1000.00` | `100.00` | `0` | refuse (`durationYears`) |
| `1000.00` | `100.00` | `31` | refuse (`durationYears`) |
| `1000,00` | `100.00` | `5` | refuse (`initialAmount`, format de fil) |

Bornes et cles de message :

| champ | forme du fil | borne inclusive | cle de message de borne |
| --- | --- | --- | --- |
| `initialAmount` | `^[0-9]{1,6}\.[0-9]{2}$` | 100.00 - 100000.00 | `simulation.initialAmount.range` |
| `monthlyAmount` | `^[0-9]{1,6}\.[0-9]{2}$` | 0.00 - 2000.00 | `simulation.monthlyAmount.range` |
| `durationYears` | entier JSON | 1 - 30 | `simulation.durationYears.range` |

Un defaut de forme du fil (virgule decimale, separateur de milliers, nombre de decimales) est
signale une seule fois, par la cle `*.format` : la borne de valeur ne redouble pas ce verdict.

Un montant malforme est une erreur de contrainte produisant un **400** structure en
`application/problem+json`, jamais un 500.

Le tableau est execute par
`src/test/java/fr/demo/souscription/api/validation/AmountBoundaryContractTest.java` ; la table
identique doit passer dans `souscription-simulation-service`.
