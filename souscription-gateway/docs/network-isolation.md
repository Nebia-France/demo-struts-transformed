# Isolation réseau des services aval — souscription-gateway (CFG-011 / W4-T03)

Plan 19, compagnon de `deploy/networkpolicy.yaml`.

Les contrôles de bordure de la passerelle (CSRF, liste blanche de méthodes, en-têtes de sécurité,
bornes d'entrée JSON) ne valent que si **aucun autre chemin** ne mène aux trois services aval. Sans
isolation réseau, un appelant atteignant directement `souscription-service` contourne la totalité de
ces contrôles. L'isolation n'est donc pas un durcissement optionnel : c'est la condition de validité
de la bordure.

## Propriétaire

| Rôle | Titulaire |
| --- | --- |
| Propriétaire des `NetworkPolicy` | **équipe infrastructure / plateforme** (`owner: infrastructure-team`) |
| Propriétaire de la configuration de l'ingress partagé | équipe infrastructure / plateforme |
| Demandeur / vérificateur applicatif | équipe `souscription-gateway` |

`deploy/networkpolicy.yaml` est versionné dans ce dépôt pour traçabilité ; son application relève de
l'infrastructure. L'équipe applicative ne le modifie pas, elle vérifie son effet.

## Règles appliquées

1. `downstream-services-default-deny-ingress` : refus par défaut de tout trafic entrant vers les pods
   `souscription-catalogue-service`, `souscription-simulation-service` et `souscription-service`.
2. `downstream-services-allow-gateway` : autorisation explicite et unique du sélecteur de pod
   `app: souscription-gateway` vers le port TCP 8080 de ces trois services.
3. Les trois services ne sont **pas publiés par l'ingress partagé** : aucune `Ingress` ni aucun
   `Service` de type `LoadBalancer`/`NodePort` ne les référence. Seule la passerelle est publiée
   (même origine que la SPA, OD-004).
4. L'authentification mutuelle (`docs/mtls.md`) est la seconde couche : même en cas d'erreur de
   politique réseau, un appelant sans certificat client émis par la CA interne est rejeté.

## Procédure de vérification

À exécuter après toute modification des politiques ou de l'ingress, et à chaque revue de sécurité.

### 1. Depuis l'extérieur du cluster (poste d'un opérateur, hors VPN de cluster)

Pour chacun des trois services, en utilisant son nom public supposé ou l'IP de l'ingress avec
l'en-tête `Host` correspondant :

```bash
for svc in souscription-catalogue-service souscription-simulation-service souscription-service; do
  curl --max-time 5 -sS -o /dev/null -w "$svc -> %{http_code}\n" "https://$svc.<domaine-public>/v1/" \
    || echo "$svc -> non joignable (attendu)"
done
```

**Résultat attendu : 0 service joignable** — échec de résolution DNS ou échec de connexion (timeout
/ connection refused) pour les trois. Tout code HTTP renvoyé, y compris 401, 403 ou 404, est un
**échec de la vérification** : il prouve qu'un chemin réseau existe.

### 2. Depuis un pod quelconque du cluster, non étiqueté `app: souscription-gateway`

```bash
kubectl run net-probe --rm -it --restart=Never --image=curlimages/curl -- \
  sh -c 'for s in souscription-catalogue-service souscription-simulation-service souscription-service; do
            curl --max-time 5 -sS -o /dev/null -w "$s -> %{http_code}\n" "http://$s:8080/v1/" \
            || echo "$s -> bloque (attendu)"; done'
```

**Résultat attendu : 3 connexions bloquées** (timeout, pas de réponse HTTP).

### 3. Depuis le pod de la passerelle (contrôle positif)

```bash
kubectl exec deploy/souscription-gateway -- \
  curl --max-time 5 -sS -o /dev/null -w "%{http_code}\n" "$CATALOGUE_BASE_URI/v1/products"
```

**Résultat attendu : 200.** Sans ce contrôle positif, une politique trop restrictive passerait pour
une politique correcte.

### 4. Inventaire déclaratif

```bash
kubectl get ingress -A -o yaml | grep -c 'souscription-\(catalogue\|simulation\)\?-\?service'
```

**Résultat attendu : 0** référence des trois services aval dans les objets `Ingress`.

## Traçabilité

Le résultat des quatre étapes (date, opérateur, sortie) est consigné par l'équipe infrastructure
dans le registre de vérification de la plateforme. Un échec de l'étape 1 ou 2 est un incident de
sécurité : il invalide les contrôles de bordure décrits dans `docs/edge-filters.md`,
`docs/csrf-trust-model.md` et `docs/security-headers.md`.
