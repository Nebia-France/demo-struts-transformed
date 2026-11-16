# POOL_SIZING — dimensionnement et instrumentation du pool Agroal

Source : `SubscriptionRepository.open()` ouvrait une connexion H2 fichier via
`DriverManager.getConnection(url, "sa", "")` **a chaque operation**, sans pool, sans plafond et
avec un seul ecrivain possible. La cible remplace ce comportement par un pool Agroal unique,
dimensionne et instrumente (`quarkus.datasource.metrics.enabled=true`), expose via Micrometer
sous les series `agroal.*`.

## Valeurs appliquees

| Cle | Valeur | Raison |
| --- | --- | --- |
| `quarkus.datasource.jdbc.min-size` | `${HORIZON_DB_POOL_MIN:2}` | Deux connexions chaudes : supprime le cout d'ouverture sur le premier appel apres une periode creuse |
| `quarkus.datasource.jdbc.max-size` | `${HORIZON_DB_POOL_MAX:10}` | Plafond par replique, borne par le calcul NFG-013 ci-dessous |
| `quarkus.datasource.jdbc.acquisition-timeout` | `3S` | Echec rapide plutot qu'une file d'attente illimitee ; tout depassement est un signal NFG-008 |
| `quarkus.datasource.jdbc.leak-detection-interval` | `10S` | Detecte une connexion empruntee et non rendue (le defaut du code source : aucune fermeture garantie) |
| `quarkus.thread-pool.max-threads` | `${HORIZON_WORKER_THREADS:20}` | Plafond du pool de workers sur lequel s'executent les six operations `@Blocking` (W6-T05), cf. ratio ci-dessous |

## Ratio worker / connexion (W6-T05)

Les six operations REST portent `@Blocking` : elles s'executent sur le pool de workers Vert.x et
jamais sur la boucle d'evenements. Deux plafonds se font donc face :

| Plafond | Valeur par defaut | Variable |
| --- | --- | --- |
| Workers concurrents | **20** | `HORIZON_WORKER_THREADS` |
| Connexions JDBC par replique | **10** | `HORIZON_DB_POOL_MAX` |

**Ratio retenu : 2 workers par connexion.**

Justification. Un worker ne detient une connexion que pendant la portion transactionnelle de la
requete. Sur le chemin de confirmation — le seul qui enchaine des appels sortants — la
decomposition de `deploy/LATENCY_BUDGET.md` attribue **3550 ms** aux deux appels reseau et
**800 ms** a la transaction : un worker passe donc environ **un cinquieme** de son temps a tenir
une connexion. Un ratio de 1:1 laisserait la moitie du pool JDBC inutilisee pendant les appels
sortants ; un ratio de 5:1 (50 workers) saturerait le pool des que les requetes se synchroniseraient
sur leur phase transactionnelle. 2:1 absorbe la phase reseau sans que la file d'acquisition ne
devienne la norme.

**Pourquoi ne pas monter les workers :** plus de workers que de connexions ne produit pas de debit,
seulement de l'attente devant le pool. Cette attente est bornee par
`acquisition-timeout=3S` et se manifeste comme un **timeout d'acquisition**, c'est-a-dire
precisement l'indicateur que NFG-008 exige a zero. La regle de reglage est donc : `agroal_awaiting`
durablement > 0 avec un p95 conforme ⇒ augmenter `HORIZON_DB_POOL_MAX` dans la limite du calcul
NFG-013, **et non** `HORIZON_WORKER_THREADS`.

Les deux variables doivent etre ajustees ensemble : toute hausse de `HORIZON_WORKER_THREADS`
au-dela de 2x `HORIZON_DB_POOL_MAX` doit etre accompagnee d'une hausse correspondante du pool JDBC,
elle-meme bornee par le budget de connexions PostgreSQL ci-dessous.

## Resume numerique (NFG-008 / NFG-013)

- `max_connections` = 100 ; replicas max = 4 ; `max-size` = 10
- calcul NFG-013 : `max-size x replicas <= 80% de max_connections` → `10 x 4 = 40 <= 80` → conforme
- NFG-008 : p95 < 300 ms, 0 timeout d'acquisition
- sources de mesure : `agroal.*` (`agroal.active`, `agroal.awaiting`), `http.server.requests`,
  `pg_stat_activity`

## NFG-008 — latence et saturation

Seuils numeriques :

- **p95 de l'endpoint < 300 ms**
- **0 timeout d'acquisition**

Mesure : sous test de charge, lire les timers Micrometer exposes sur `/q/metrics` :

- `http_server_requests_seconds` (quantile 0,95, par `uri`) pour la latence ;
- `agroal_awaiting` (doit rester a 0 de maniere durable) et le compteur de timeouts Agroal,
  correle a `acquisition-timeout=3S`, pour les echecs d'acquisition.

Un p95 conforme avec `agroal_awaiting > 0` signifie que le pool est le facteur limitant :
augmenter `HORIZON_DB_POOL_MAX` dans la limite du calcul NFG-013.

## NFG-013 — budget de connexions PostgreSQL

Seuil numerique : **`max-size` x nombre maximal de repliques <= 80 % de `max_connections`**.

Valeurs retenues :

- PostgreSQL `max_connections` = **100** (valeur par defaut de l'instance managee cible)
- plafond de repliques de `souscription-service` = **4**
- calcul : `10 x 4 = 40` connexions <= `0,8 x 100 = 80` → **conforme** (50 % du budget 80 %)

Mesure : pendant un test de montee en charge (scale-out jusqu'au plafond de repliques), comparer
la somme de `agroal_active` sur les repliques avec `SELECT count(*) FROM pg_stat_activity` cote
base, et verifier `agroal_awaiting = 0`.

Marge : le budget autorise jusqu'a 8 repliques a `max-size=10`. **Au-dela de 8 repliques — ou si
`max_connections` est abaisse — la condition 80 % est rompue** : interposer alors **PgBouncer** en
mode transaction comme solution de repli, les services conservant `max-size=10` et le pooler
agregeant les connexions reelles vers PostgreSQL.

## Profils dev et test

- `%dev` : Dev Services PostgreSQL (`postgres:16-alpine`) ; les variables `HORIZON_DB_*` ne sont
  pas requises localement.
- `%test` : Dev Services et le pool sont **desarmes par defaut**
  (`%test.quarkus.datasource.devservices.enabled=false`, `%test.quarkus.datasource.jdbc=false`).
  Un daemon de conteneurs indisponible faisait echouer au demarrage tous les `@QuarkusTest`,
  y compris ceux sans persistance. Les tests qui ont besoin de la base les reactivent via
  `@TestProfile(PostgresDevServicesProfile.class)` et sont ignores sans daemon
  (`@RequiresContainerRuntime`). En CI avec Docker, `DatasourceSmokeTest` s'execute
  et prouve le cablage du pool.

## Rollback

Le monolithe continue de fonctionner sur H2 : `src/main/resources/db/schema.sql` et la classe
`SubscriptionRepository` du source restent inchanges et constituent la cible de rollback.
