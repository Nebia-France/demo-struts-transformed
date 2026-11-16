# mTLS sortant — souscription-gateway (W4-T03)

Plan 19. Les trois arêtes sortantes de la passerelle (`catalogue`, `simulation`, `subscription`)
sont authentifiées mutuellement : la passerelle présente un certificat client et vérifie le
certificat serveur **ainsi que son nom d'hôte**. C'est le modèle d'authentification
service-à-service confirmé en clarification (mTLS), complément indispensable de l'isolation réseau
décrite dans `docs/network-isolation.md`.

## Configuration

`src/main/resources/application.properties`, répétée à l'identique pour les trois `configKey` :

```properties
%prod.quarkus.rest-client.catalogue.key-store=file:/etc/gateway/tls/keystore.p12
%prod.quarkus.rest-client.catalogue.key-store-password=${TLS_KEYSTORE_PASSWORD}
%prod.quarkus.rest-client.catalogue.trust-store=file:/etc/gateway/tls/truststore.p12
%prod.quarkus.rest-client.catalogue.trust-store-password=${TLS_TRUSTSTORE_PASSWORD}
quarkus.rest-client.catalogue.verify-host=true
```

- Les **chemins** et les **mots de passe** sont préfixés `%prod.` : en test et en dev-mode les
  doublures WireMock sont en clair sur `localhost` et aucun matériel n'est requis. En prod,
  l'absence d'une des deux variables d'environnement fait échouer le démarrage — il n'existe
  aucune valeur par défaut.
- `verify-host=true` est déclaré **dans tous les profils**. Le passer à `false` « pour débloquer le
  dev » annule l'authentification du serveur et doit être refusé en revue ;
  `src/test/java/.../client/MtlsTest.java` échoue si la propriété disparaît ou passe à `false`.
- **Aucun repli TLS sans authentification du client n'est autorisé** : il n'existe pas de profil,
  de variable ni d'option permettant d'appeler un aval en TLS simple ou en clair hors
  dev/test local. Un aval qui ne réclame pas de certificat client est une anomalie à remonter à
  l'équipe infrastructure, pas à contourner côté passerelle.
- Aucun fichier `.p12` n'est versionné ni embarqué dans l'image : le secret Kubernetes
  `souscription-gateway-mtls` est monté en lecture seule (`defaultMode: 0400`) sur
  `/etc/gateway/tls` par `deploy/deployment.yaml`, et les deux mots de passe sont injectés par
  `secretKeyRef`.

## Matériel et autorité de certification

| Élément | Valeur |
| --- | --- |
| CA émettrice | CA interne de la plateforme (`infrastructure-team`), hors chaîne publique |
| Keystore client | `/etc/gateway/tls/keystore.p12` (PKCS#12), clé + certificat de `souscription-gateway` |
| Truststore | `/etc/gateway/tls/truststore.p12` (PKCS#12), certificat de la CA interne uniquement |
| Propriétaire du secret | équipe infrastructure / plateforme |
| SAN attendus côté aval | nom de service interne (`souscription-*-service.<ns>.svc.cluster.local`) |

Les URL des trois avals (`CATALOGUE_BASE_URI`, `SIMULATION_BASE_URI`, `SUBSCRIPTION_BASE_URI`)
doivent donc utiliser **exactement** le nom présent dans les SAN du certificat serveur : une URL
par adresse IP échoue, par construction, puisque `verify-host=true`.

## Rotation

| Point | Règle |
| --- | --- |
| Cadence de rotation | certificats de feuille tous les 90 jours, CA interne tous les 5 ans |
| Déclencheur | rotation planifiée, ou immédiate sur suspicion de compromission |
| Procédure | l'infrastructure met à jour le secret `souscription-gateway-mtls`, puis redémarre progressivement le déploiement (`kubectl rollout restart`) — la passerelle lit le matériel au démarrage |
| Pré-requis | le nouveau certificat de CA est ajouté au truststore **avant** le retrait de l'ancien (chevauchement des deux CA) pour que la rotation soit sans coupure |
| Ordre | truststore d'abord (côté passerelle et côté avals), keystore ensuite |
| Surveillance d'expiration | jauge `gateway_tls_certificate_expiry_seconds{client,store}` (date `notAfter`, `CertificateExpiryMetrics`) exposée sur l'interface de management ; câblée au **plan 26**, avec alerte à J-30 et alerte critique à J-7 (`deploy/alerts.yaml`, `docs/observability.md`) |
| Vérification post-rotation | un appel nominal sur chacune des trois arêtes doit répondre 2xx ; un appel depuis un pod dépourvu du keystore doit être refusé |

La passerelle étant sans état, un redémarrage progressif n'entraîne aucune perte fonctionnelle ;
le `PodDisruptionBudget` (`deploy/pdb.yaml`) garantit la disponibilité pendant l'opération.

## Test

`src/test/java/fr/demo/souscription/gateway/client/MtlsTest.java` monte un serveur HTTPS exigeant
un certificat client (WireMock, `needClientAuth`) à partir de matériel **auto-signé généré sous
`target/`** par `keytool` au lancement du test — jamais de secret versionné. Il vérifie que :

1. un client **sans** keystore est refusé à la poignée de main ;
2. le même client **avec** le keystore de test est accepté ;
3. un certificat serveur dont le nom ne correspond pas à l'hôte appelé échoue tant que la
   vérification d'hôte est active ;
4. `application.properties` déclare `verify-host=true` sur les trois clients, et que la valeur
   `false` n'apparaît nulle part pour cette propriété.
