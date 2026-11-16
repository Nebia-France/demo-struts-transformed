# Alertes de retention du brouillon — souscription-service

Deux regles d'alerte a installer avec la purge planifiee (mitigation R-002). Elles remplacent
l'expiration implicite du `<session-timeout>20</session-timeout>` du conteneur de servlets : la
retention est desormais explicite (`horizon.draft.retention`, defaut `PT20M`, portee par la
colonne `subscriptions.subscription_draft.expires_at`) et la liberation est une suppression de
lignes planifiee (`horizon.draft.purge.cron`, defaut toutes les 5 minutes).

Metrique exposee sur `/q/metrics` par `quarkus-micrometer-registry-prometheus`. Le tableau de bord
qui agrege ces regles est cable par le plan `26-souscription-service-metrics-tracing`.

## 1. Purge inactive

| | |
|---|---|
| Expression | `increase(horizon_draft_purged_total[1h]) == 0` alors que le trafic de creation de brouillons est non nul sur la meme fenetre |
| Fenetre | 1 heure |
| Severite | majeure |
| Signification | la tache planifiee ne tourne plus (scheduler desactive, transaction en echec, cron errone) : les brouillons expires ne sont plus liberes |
| Action | verifier les journaux de `DraftRetentionJob`, l'etat du scheduler et la valeur de `horizon.draft.purge.cron` |

## 2. Accumulation de lignes

| | |
|---|---|
| Expression | nombre de lignes (*row count*) de `subscriptions.subscription_draft` en croissance monotone (*monotonic growth*) sur 2 heures consecutives |
| Fenetre | 2 heures |
| Severite | critique |
| Signification | les creations depassent durablement les purges : la table croit sans borne, ce qui est le mode de defaillance de la mitigation R-002 |
| Action | correler avec `horizon_draft_purged_total` et la frequence du cron ; la table doit presenter un nombre de lignes **stable** dans le temps a trafic constant |

## Lien avec NFG-011

La stabilite du nombre de lignes de `subscription_draft` est l'un des trois criteres de NFG-011
(cf. `STATELESSNESS.md`). Une croissance monotone invalide le critere meme si le service ne detient
aucun etat en memoire.
