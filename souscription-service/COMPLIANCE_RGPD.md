# Conformite RGPD / CNIL du brouillon persiste (W7-T07)

La source conservait l'identite du souscripteur en memoire, dans la session HTTP de servlet, pour
20 minutes au plus (`<session-timeout>20</session-timeout>`). La cible la persiste dans
`subscriptions.subscription_draft` (migration `V2__subscription_draft.sql`). Ce deplacement cree un
**nouveau stockage de donnees a caractere personnel** qui n'existait pas auparavant : c'est l'ecart
CFG-011. Ce document porte les obligations qui en decoulent.

Perimetre : la table `subscriptions.subscription_draft` uniquement. La table
`subscriptions.subscriptions` releve d'une finalite **contractuelle** distincte, avec une duree de
conservation propre ; les deux politiques ne sont pas fusionnees (cf. section 7).

## 1. Minimisation

Revue colonne par colonne de `subscription_draft` au regard de ce que la **reprise du parcours**
exige strictement. Les colonnes non personnelles (produit, montants, horodatages, `version`) sont
listees pour completude mais ne sont pas des donnees a caractere personnel.

| colonne | finalite | strictement necessaire ? | decision |
| --- | --- | --- | --- |
| `draft_id` | jeton opaque de reprise du parcours (UUIDv4 non enumerable) | oui — seule cle portee par le SPA | conservee |
| `confirmation_key` | invalidation d'une confirmation obsolete apres mutation du brouillon | oui | conservee |
| `product_code`, `product_name`, `product_profile`, `annual_rate` | produit choisi, reaffiche aux etapes 2 a 4 et recopie a la confirmation | oui — donnees de catalogue, non personnelles | conservees |
| `first_name` | reaffichage du formulaire a la reprise, puis recopie dans la souscription confirmee | oui — sans elle la reprise exige une nouvelle saisie et la confirmation est impossible (`first_name` est `NOT NULL` dans `subscriptions`) | conservee |
| `last_name` | idem `first_name` | oui — meme raison | conservee |
| `email` | identification du souscripteur au recapitulatif et recopie a la confirmation | oui — `email` est `NOT NULL` dans `subscriptions` | conservee |
| `birth_date` | controle d'age minimum (`@MinimumAge`) revalide a la confirmation, et recopie dans la souscription | oui — le verdict d'age ne peut pas etre reconstitue sans elle | conservee |
| `initial_amount`, `monthly_amount`, `duration_years`, `contributed`, `final_capital` | etat de la simulation, reaffiche au recapitulatif | oui — donnees financieres du parcours, non identifiantes par elles-memes | conservees |
| `version` | verrou optimiste des ecritures concurrentes | oui | conservee |
| `created_at`, `updated_at` | tracabilite technique du brouillon | oui | conservees |
| `expires_at` | materialisation de la retention et critere de purge | oui | conservee |

**Conclusion explicite : aucune colonne n'est retiree.** Les quatre colonnes personnelles
(`first_name`, `last_name`, `email`, `birth_date`) sont exactement les quatre champs du formulaire
souscripteur source, tous requis soit par la reprise du parcours, soit par la revalidation a la
confirmation. Il n'y a donc **pas** de migration `V3__draft_minimisation.sql` : une migration vide
ou cosmetique serait un artefact trompeur. Si une evolution ulterieure rend une colonne superflue,
elle devra etre retiree par une migration forward-only dediee, avec sa justification en en-tete.

Aucune donnee non necessaire n'est collectee en plus : le brouillon ne porte ni adresse postale, ni
telephone, ni identifiant national, ni donnee de categorie particuliere (article 9).

## 2. Retention

- Valeur : **`PT20M`** (`horizon.draft.retention` dans `application.properties`). Equivalence
  stricte avec le `<session-timeout>20</session-timeout>` source — cette valeur par defaut ne doit
  pas etre modifiee sans nouvelle arbitrage DPO.
- Mecanisme : `expires_at = created_at + PT20M`, index `idx_subscription_draft_expires_at`, purge
  planifiee `application/DraftRetentionJob` (`horizon.draft.purge.cron = 0 */5 * * * ?`).
- Un brouillon expire est **illisible immediatement**, sans attendre le cron : le chemin de lecture
  filtre sur `expires_at` (`DraftService.read`, 404).

Preuve que la purge est effective et pas seulement configuree :

```sql
SELECT count(*) FROM subscriptions.subscription_draft WHERE expires_at < now();
```

Cette requete de controle doit renvoyer **0** hors fenetre de purge (c'est-a-dire ailleurs qu'entre
l'expiration d'une ligne et le passage suivant du cron, soit 5 minutes au plus). Elle est executee
apres un cycle de purge par `DraftComplianceTest`.

Preuve continue en exploitation : le compteur **`horizon.draft.purged`** (declare par
`observability/BusinessMetrics`), incremente du nombre de lignes reellement supprimees. Les regles
d'alerte associees — compteur nul sur une heure de trafic, volumetrie de `subscription_draft`
croissante — sont dans `deploy/DRAFT_ALERTS.md`. Une purge qui echoue silencieusement est donc
detectee, et non supposee.

## 3. Journalisation

Aucune donnee a caractere personnelle n'est journalisee : un brouillon est journalise par son
`draftId` seul. Les champs `firstName`, `lastName`, `email` et `birthDate` n'apparaissent dans
aucune ligne de journal. La regle est posee par `logging/LoggingConventions` ; elle devient ici une
obligation **reglementaire** et non une simple hygiene, d'ou sa re-affirmation par un test dedie
(`DraftComplianceTest`, en complement de `observability/CorrelatedLoggingTest`).

Les journaux etant exportes hors du cluster, une fuite par ce canal echapperait a la retention de
20 minutes : c'est la raison pour laquelle la regle est testee et non documentee seulement.

## 4. Droit a l'effacement

La purge automatique couvre le cas courant (toute donnee de brouillon disparait au plus 25 minutes
apres sa saisie). Procedure a la demande, pour une demande recue avant l'echeance :

1. **Brouillon** — effacement par `draftId` :
   `persistence.DraftRepository.deleteByDraftId(UUID)`, invoque par une tache operatoire ou une
   console d'administration interne authentifiee. Equivalent SQL :

   ```sql
   DELETE FROM subscriptions.subscription_draft WHERE draft_id = :draftId;
   ```

2. **Souscription confirmee** — effacement par `reference`, sous reserve de la base legale
   contractuelle et des obligations de conservation applicables (une souscription confirmee ne peut
   etre effacee sur simple demande si elle est encore couverte par une obligation legale de
   conservation) :

   ```sql
   DELETE FROM subscriptions.subscriptions WHERE reference = :reference;
   ```

**Aucun point d'entree HTTP d'effacement n'est expose.** L'API `/v1` est non authentifiee : un
`DELETE /v1/drafts/{draftId}` permettrait a quiconque detenant un identifiant de detruire le
parcours d'un tiers. L'effacement reste une operation d'exploitation, tracee hors du service.

## 5. Registre des traitements

| rubrique | brouillon de souscription (`subscription_draft`) | souscription confirmee (`subscriptions`) |
| --- | --- | --- |
| finalite | permettre la reprise d'un parcours de souscription en cours (etapes 1 a 4) | conserver la souscription conclue |
| categories de personnes | prospects saisissant un parcours de souscription | souscripteurs |
| categories de donnees | prenom, nom, adresse electronique, date de naissance, produit choisi, parametres et resultat de simulation | memes categories, au titre du contrat |
| base legale | interet legitime du responsable de traitement (art. 6.1.f) : fournir un parcours utilisable et repreneable, sans re-saisie | execution du contrat (art. 6.1.b) |
| duree de conservation | **20 minutes** (`PT20M`), purge planifiee toutes les 5 minutes | duree contractuelle et legale applicable — **politique distincte**, cf. section 7 |
| destinataires | aucun tiers. Acces interne limite a `souscription-service` ; l'entree reseau est restreinte a `souscription-gateway` (`deploy/network-policy.yaml`, `deploy/authorization-policy.yaml`) | idem |
| transferts hors UE | aucun | aucun |
| sous-traitants | hebergeur de la plateforme et du service de base de donnees gere | idem |
| mesures de securite | chiffrement au repos et en transit (section 6), mTLS de maillage, aucun identifiant en dur, pas de donnee personnelle en journal | idem |

### Validation DPO — **point de decision bloquant**

> **DPO validation : NON ENREGISTREE — BLOQUANT POUR LA MISE EN SERVICE.**
>
> Le service ne doit pas etre mis en service tant que cette ligne n'est pas remplacee par une
> validation nominative et datee du Delegue a la protection des donnees portant sur : la base legale
> retenue pour le brouillon (interet legitime), la duree de 20 minutes, l'inscription au registre des
> traitements et le mecanisme de chiffrement au repos effectivement fourni par la plateforme.
>
> A completer par le DPO : `validé par <nom> — <date> — <reference de l'inscription au registre>`.

Cette ligne est la trace de l'arbitrage exige par le plan ; elle est volontairement laissee
non signee, le projet ne pouvant pas se valider lui-meme.

## 6. Chiffrement

- **Au repos** : chiffrement fourni par le service de base de donnees gere / le volume, au niveau du
  volume (chiffrement de disque par la plateforme, cle geree par le gestionnaire de cles du cloud
  provider, rotation a la charge de la plateforme). Le service applicatif ne chiffre pas les colonnes
  lui-meme : un chiffrement applicatif par colonne priverait la purge de son index sur `expires_at`
  sans reduire la surface reelle, la donnee vivant 20 minutes. Exigence : **aucune instance de base
  de donnees non chiffree au repos** ne doit heberger `subscriptions.subscription_draft`.
- **En transit** : TLS impose cote client JDBC par
  `quarkus.datasource.jdbc.additional-jdbc-properties.sslmode=require` (plan 07,
  `application.properties`). Les appels sortants vers le catalogue et la simulation sont en mTLS
  TLS 1.3 (`quarkus.tls.mesh.*`).
- Reference de deploiement : `deploy/secret-ref.yaml` (section « Chiffrement au repos »), qui porte
  les references de secret de la base de donnees et renvoie a ce document.

## 7. Deux politiques de retention separees

`subscriptions.subscriptions` contient les memes categories de donnees personnelles mais sous une
finalite **contractuelle** et une duree de conservation differente. Les deux politiques restent
distinctes :

- `DraftRetentionJob` ne supprime **que** dans `subscription_draft`. Il n'ouvre jamais
  `subscriptions.subscriptions`.
- Appliquer `PT20M` a la table contractuelle detruirait des souscriptions conclues ; appliquer la
  duree contractuelle au brouillon conserverait l'identite de prospects n'ayant jamais souscrit.
- Cette separation est verifiee par test (`DraftComplianceTest`) et non par convention.

## 8. Controle de parite (plan 35 parity)

Ce plan n'a **aucune reference source a preserver** : la persistance du brouillon est une
obligation creee par la migration (CFG-011). Le critere de parite est donc negatif — la donnee du
souscripteur ne doit etre ni **plus exposee** ni **conservee plus longtemps** que dans la source
(session HTTP, 20 minutes au plus).

Les treize points de controle du plan ont ete verifies ; aucun ecart detecte, donc aucune
remediation appliquee. Les proprietes sont desormais tenues par deux tests installes a cette
occasion, en complement de `DraftComplianceTest` :

| test | portee | conteneur requis |
| --- | --- | --- |
| `application/DraftCompliancePolicyParityTest` | `horizon.draft.retention=PT20M` et `sslmode=require` effectifs ; `DraftRetentionJob` ne nomme jamais la table contractuelle ; `deleteByDraftId` present et **aucun** point d'entree `@DELETE` sous `api/` (enumeration par reflexion des classes compilees) ; coherence decision de minimisation / absence de `V3__draft_minimisation.sql` ; completude du registre (finalite, base legale, duree, destinataires), requete de controle, compteur `horizon.draft.purged`, mecanisme de chiffrement au repos nomme, ligne DPO bloquante | non — execute a chaque build |
| `application/DraftComplianceParityTest` | 0 brouillon expire apres un cycle de purge (requete de controle de la section 2) ; volumetrie de `subscriptions` inchangee apres purge ; `deleteByDraftId` supprimant exactement une ligne ; aucune donnee personnelle en journal pendant le traitement d'un brouillon | oui (profil Dev Services PostgreSQL, `@RequiresContainerRuntime`) |

Les trois defauts bloquants pour la mise en service restent : une purge inoperante, une purge
pouvant atteindre la table contractuelle, et un magasin de donnees personnelles non valide par le
DPO (section 5).
