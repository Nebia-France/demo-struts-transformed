# Alertes du pool JDBC — souscription-service

Deux regles d'alerte a installer des la premiere mise en service (mitigation R-003). Le pool
Agroal est la nouvelle ressource partagee et bornee du service : son epuisement se manifeste
comme une latence de confirmation, pas comme une erreur de base de donnees. Les seuils
completent le dimensionnement decrit dans `POOL_SIZING.md`.

Metriques exposees par `quarkus.datasource.metrics.enabled=true` sur `/q/metrics`.

## 1. Epuisement du pool

| | |
|---|---|
| Expression | `agroal_awaiting_count{datasource="default"} > 0` |
| Duree | 1 minute |
| Severite | critique |
| Signification | des requetes attendent une connexion : le pool (`HORIZON_DB_POOL_MAX`) est entierement occupe |
| Action | verifier les requetes lentes et le ratio `max-size x replicas` vis-a-vis de `max_connections` (NFG-013) avant toute augmentation |

## 2. Latence d'acquisition

| | |
|---|---|
| Expression | p95 de `agroal_acquire_seconds` (ou `agroal_blocking_time_average_milliseconds`) superieur au delai d'acquisition configure, soit 3 s (`quarkus.datasource.jdbc.acquisition-timeout=3S`) |
| Fenetre | 5 minutes |
| Severite | majeure |
| Signification | les acquisitions approchent du timeout : les prochaines requetes echoueront en 503 plutot que d'attendre |
| Action | correler avec `agroal_awaiting_count` et la latence cote PostgreSQL ; objectif NFG-008 : p95 < 300 ms et zero timeout d'acquisition |

## Portee de la validation NFG-011

NFG-011 (N instances, aucune affinite de session, aucun etat par utilisateur en memoire) n'est
que **partiellement** valide a ce stade. La couche de donnees n'est plus mono-ecrivain : l'etat
des souscriptions est dans PostgreSQL et plusieurs instances peuvent ecrire en parallele. La
suppression de la session HTTP (brouillon de souscription) appartient a la vague 2 ; jusque-la,
l'absence d'affinite ne peut pas etre affirmee de bout en bout.
