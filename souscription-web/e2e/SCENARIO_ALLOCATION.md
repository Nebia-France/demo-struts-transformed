# Allocation des scénarios Gherkin (W8-T02 / W8-T03)

`RAPPORT_COUVERTURE_GHERKIN.md` déclare **88 scénarios** (66 `Scenario` + 22 `Scenario Outline`)
répartis sur 9 domaines. Chaque scénario est alloué ci-dessous au tier qui l'asserte et au fichier
de test qui l'implémente. Aucun scénario n'est orphelin : `npm run check:allocation`
(`scripts/check-allocation.mjs`, branché dans `lint`) échoue si un titre d'un
`e2e/features/*.feature` est absent de ce fichier, si un titre alloué n'existe plus, ou si le total
alloué n'est pas 88.

Les titres de la colonne « Scénario » sont copiés **octet pour octet** depuis les `.feature`
(graphie sans diacritique incluse) : les filtres `--name` de cucumber-js en dépendent.

Tiers :

- **SPA** : tests unitaires / composants Vitest de `souscription-web` (`src/**/*.spec.ts`) et
  step definitions `e2e/steps/*.steps.ts`.
- **e2e** : parcours navigateur Playwright (`e2e/journeys/*.spec.ts`) et suite cucumber-js.
- **souscription-gateway**, **souscription-service**, **souscription-catalogue-service**,
  **souscription-simulation-service** : suites d'acceptation des applications backend, qui
  rejouent le `.feature` du domaine (référence legacy : `features/step_definitions/*Steps.java`).

## Allocation par domaine

| Domaine (`features/...`) | Scénarios déclarés | Tier qui asserte |
| --- | --- | --- |
| `catalogue/catalogue.feature` | 8 | gateway + catalogue-service (règles) ; SPA (rendu liste/fiche) |
| `validation-souscripteur/validation-souscripteur.feature` | 10 | souscription-service (autoritaire) ; SPA (retour UX) |
| `simulation/simulation.feature` | 10 | simulation-service (calcul, bornes) ; SPA (saisie/format) |
| `parcours-souscription/parcours-souscription.feature` | 15 | e2e navigateur (séquencement, reprises) + souscription-service (brouillon) |
| `confirmation-souscription/confirmation-souscription.feature` | 13 | souscription-service (idempotence) + e2e (rejeu, affichage) |
| `persistance-souscriptions/persistance-souscriptions.feature` | 9 | souscription-service uniquement (hors périmètre SPA) |
| `securite-web/securite-web.feature` | 11 | gateway (CSRF, méthodes, encodage) + e2e (jeton absent, ressources statiques) |
| `initialisation-application/initialisation-application.feature` | 8 | services backend uniquement (hors périmètre SPA) |
| `formulaire-souscription/formulaire-souscription.feature` | 4 | SPA (valeurs par défaut, conservation de la saisie) |
| **Total** | **88** | |

## Allocation par scénario

<!-- allocation-table:start -->
| # | Domaine | Scénario | Tier | Fichier(s) de test |
| --- | --- | --- | --- | --- |
| 1 | `catalogue/catalogue.feature` | Consultation de la liste des placements | SPA (rendu liste) + souscription-catalogue-service | `src/app/catalogue/catalogue-list.component.spec.ts`, `e2e/steps/catalogue.steps.ts` |
| 2 | `catalogue/catalogue.feature` | Consultation de la fiche d'un placement existant | SPA (rendu fiche) + souscription-catalogue-service | `src/app/catalogue/product-detail.component.spec.ts`, `e2e/steps/catalogue.steps.ts` |
| 3 | `catalogue/catalogue.feature` | Le taux annuel est egalement expose en pourcentage | SPA (format) + souscription-catalogue-service | `src/app/core/locale/rate.spec.ts`, `src/app/catalogue/product-detail.component.spec.ts` |
| 4 | `catalogue/catalogue.feature` | Consultation de la fiche d'un placement inconnu | souscription-gateway + souscription-catalogue-service (404) ; SPA (redirection) | `src/app/catalogue/catalogue.resolvers.spec.ts`, `e2e/steps/catalogue.steps.ts` |
| 5 | `catalogue/catalogue.feature` | Code produit vide ou mal formate sur la fiche produit | souscription-gateway (validation du chemin) ; SPA (routage) | `src/app/app.routes.spec.ts`, `src/app/catalogue/catalogue.resolvers.spec.ts` |
| 6 | `catalogue/catalogue.feature` | Recherche d'un produit sans code transmis | souscription-catalogue-service ; SPA (routage) | `src/app/app.routes.spec.ts`, `e2e/steps/catalogue.steps.ts` |
| 7 | `catalogue/catalogue.feature` | Methode HTTP non autorisee sur le catalogue | souscription-gateway (liste blanche des méthodes) | `e2e/steps/catalogue.steps.ts` (contre la gateway), `scripts/check-http-methods.mjs` |
| 8 | `catalogue/catalogue.feature` | La liste des produits n'est pas modifiable | souscription-catalogue-service ; SPA (lecture seule) | `src/app/catalogue/catalogue.facade.spec.ts`, `e2e/steps/catalogue.steps.ts` |
| 9 | `validation-souscripteur/validation-souscripteur.feature` | Saisie complete et valide du souscripteur | souscription-service (autoritaire) ; SPA (UX) | `src/app/wizard/subscriber-step.component.spec.ts`, `e2e/steps/validation-souscripteur.steps.ts` |
| 10 | `validation-souscripteur/validation-souscripteur.feature` | Les espaces superflus sont supprimes des donnees du souscripteur | souscription-service (autoritaire) ; SPA (UX) | `src/app/wizard/subscriber-step.component.spec.ts`, `e2e/steps/validation-souscripteur.steps.ts` |
| 11 | `validation-souscripteur/validation-souscripteur.feature` | Prenom ou nom invalide | souscription-service (autoritaire) ; SPA (UX) | `e2e/api/validation.spec.ts`, `src/app/shared/forms/apply-server-errors.spec.ts`, `e2e/steps/validation-souscripteur.steps.ts` |
| 12 | `validation-souscripteur/validation-souscripteur.feature` | Longueurs limites acceptees pour le prenom | souscription-service (autoritaire) ; SPA (UX) | `src/app/wizard/subscriber-step.component.spec.ts`, `e2e/steps/validation-souscripteur.steps.ts` |
| 13 | `validation-souscripteur/validation-souscripteur.feature` | Un caractere de controle dans le nom est refuse | souscription-service (autoritaire) ; SPA (UX) | `e2e/api/validation.spec.ts`, `e2e/steps/validation-souscripteur.steps.ts` |
| 14 | `validation-souscripteur/validation-souscripteur.feature` | Adresse electronique invalide | souscription-service (autoritaire) ; SPA (UX) | `e2e/api/validation.spec.ts`, `src/app/wizard/subscriber-step.component.spec.ts`, `e2e/steps/validation-souscripteur.steps.ts` |
| 15 | `validation-souscripteur/validation-souscripteur.feature` | Une adresse electronique de plus de 254 caracteres est refusee | souscription-service (autoritaire) ; SPA (UX) | `e2e/api/validation.spec.ts`, `e2e/steps/validation-souscripteur.steps.ts` |
| 16 | `validation-souscripteur/validation-souscripteur.feature` | Date de naissance invalide ou non conforme | souscription-service (autoritaire) ; SPA (UX) | `e2e/api/validation.spec.ts`, `src/app/core/locale/date.spec.ts`, `e2e/steps/validation-souscripteur.steps.ts` |
| 17 | `validation-souscripteur/validation-souscripteur.feature` | Age limite de la majorite | souscription-service (autoritaire) ; SPA (UX) | `e2e/api/validation.spec.ts`, `e2e/steps/validation-souscripteur.steps.ts` |
| 18 | `validation-souscripteur/validation-souscripteur.feature` | La construction du souscripteur est refusee en cas d'erreur de saisie | souscription-service (autoritaire) ; SPA (résumé d'erreurs) | `src/app/shared/forms/error-summary.component.spec.ts`, `e2e/steps/validation-souscripteur.steps.ts` |
| 19 | `simulation/simulation.feature` | Simulation d'un placement standard | souscription-simulation-service (calcul) ; SPA (affichage) | `src/app/shared/simulation-result.component.spec.ts`, `e2e/steps/simulation.steps.ts` |
| 20 | `simulation/simulation.feature` | Validation reussie des montants de simulation | souscription-simulation-service ; SPA (saisie) | `src/app/wizard/simulation-step.component.spec.ts`, `e2e/steps/simulation.steps.ts` |
| 21 | `simulation/simulation.feature` | Bornes du versement initial | souscription-simulation-service (bornes) ; SPA (saisie) | `e2e/api/validation.spec.ts`, `e2e/steps/simulation.steps.ts` |
| 22 | `simulation/simulation.feature` | Bornes de la mensualite | souscription-simulation-service (bornes) ; SPA (saisie) | `e2e/api/validation.spec.ts`, `e2e/steps/simulation.steps.ts` |
| 23 | `simulation/simulation.feature` | Bornes de la duree en annees | souscription-simulation-service (bornes) ; SPA (saisie) | `e2e/api/validation.spec.ts`, `e2e/steps/simulation.steps.ts` |
| 24 | `simulation/simulation.feature` | Format de montant refuse | souscription-simulation-service ; SPA (format) | `src/app/core/locale/amount.spec.ts`, `e2e/api/validation.spec.ts`, `e2e/steps/simulation.steps.ts` |
| 25 | `simulation/simulation.feature` | Normalisation des montants saisis | souscription-simulation-service ; SPA (format) | `src/app/core/locale/amount.spec.ts`, `e2e/steps/simulation.steps.ts` |
| 26 | `simulation/simulation.feature` | Parametres de simulation rejetes par le moteur de calcul | souscription-simulation-service (calcul) ; SPA (erreurs serveur) | `src/app/shared/forms/apply-server-errors.spec.ts`, `e2e/steps/simulation.steps.ts` |
| 27 | `simulation/simulation.feature` | Simulation avec une hypothese de progression nulle | souscription-simulation-service (calcul) | `src/app/api/simulation-api.service.spec.ts`, `e2e/steps/simulation.steps.ts` |
| 28 | `simulation/simulation.feature` | Simulation sans mensualite | souscription-simulation-service (calcul) ; SPA (saisie) | `src/app/wizard/simulation-step.component.spec.ts`, `e2e/steps/simulation.steps.ts` |
| 29 | `parcours-souscription/parcours-souscription.feature` | Demarrage du parcours depuis un produit du catalogue | e2e navigateur + souscription-service (brouillon) | `e2e/journeys/full-journey.spec.ts`, `e2e/steps/parcours-souscription.steps.ts` |
| 30 | `parcours-souscription/parcours-souscription.feature` | Parcours complet jusqu'au recapitulatif | e2e navigateur + souscription-service (brouillon) | `e2e/journeys/full-journey.spec.ts`, `e2e/steps/parcours-souscription.steps.ts` |
| 31 | `parcours-souscription/parcours-souscription.feature` | Recalcul de la simulation sans quitter l'etape | e2e navigateur ; SPA | `src/app/wizard/simulation-step.component.spec.ts`, `e2e/steps/parcours-souscription.steps.ts` |
| 32 | `parcours-souscription/parcours-souscription.feature` | Les donnees deja saisies sont reaffichees a l'etape souscripteur | e2e navigateur (reprise) + souscription-service (brouillon) | `src/app/wizard/repopulate.spec.ts`, `e2e/steps/parcours-souscription.steps.ts` |
| 33 | `parcours-souscription/parcours-souscription.feature` | Les donnees de simulation deja saisies sont reaffichees | e2e navigateur (reprise) + souscription-service (brouillon) | `src/app/wizard/repopulate.spec.ts`, `e2e/steps/parcours-souscription.steps.ts` |
| 34 | `parcours-souscription/parcours-souscription.feature` | Saisie invalide du souscripteur a l'etape 2 | e2e navigateur + souscription-service | `src/app/wizard/subscriber-step.component.spec.ts`, `e2e/steps/parcours-souscription.steps.ts` |
| 35 | `parcours-souscription/parcours-souscription.feature` | Saisie invalide de la simulation a l'etape 3 | e2e navigateur + souscription-simulation-service | `src/app/wizard/simulation-step.component.spec.ts`, `e2e/steps/parcours-souscription.steps.ts` |
| 36 | `parcours-souscription/parcours-souscription.feature` | Commande inconnue a l'etape de simulation | souscription-gateway (routage) ; e2e navigateur | `src/app/app.routes.spec.ts`, `e2e/steps/parcours-souscription.steps.ts` |
| 37 | `parcours-souscription/parcours-souscription.feature` | Demarrage du parcours avec un produit inconnu | e2e navigateur + souscription-catalogue-service | `src/app/catalogue/catalogue.resolvers.spec.ts`, `e2e/steps/parcours-souscription.steps.ts` |
| 38 | `parcours-souscription/parcours-souscription.feature` | Methode HTTP non conforme a l'etape du parcours | souscription-gateway (liste blanche des méthodes) | `e2e/steps/parcours-souscription.steps.ts` (contre la gateway), `scripts/check-http-methods.mjs` |
| 39 | `parcours-souscription/parcours-souscription.feature` | Chemin inconnu dans le parcours | e2e navigateur (repli de routage) | `src/app/app.routes.spec.ts`, `e2e/steps/parcours-souscription.steps.ts` |
| 40 | `parcours-souscription/parcours-souscription.feature` | Reprise du parcours sans brouillon en session | e2e navigateur (reprise) + souscription-service (brouillon) | `e2e/journeys/draft-expiry.spec.ts`, `src/app/wizard/draft-window.spec.ts`, `e2e/steps/parcours-souscription.steps.ts` |
| 41 | `parcours-souscription/parcours-souscription.feature` | Acces a la simulation avant l'identification du souscripteur | e2e navigateur (séquencement) + souscription-service | `src/app/wizard/wizard.guards.spec.ts`, `e2e/steps/parcours-souscription.steps.ts` |
| 42 | `parcours-souscription/parcours-souscription.feature` | Acces au recapitulatif avant la simulation | e2e navigateur (séquencement) + souscription-service | `src/app/wizard/wizard.guards.spec.ts`, `e2e/steps/parcours-souscription.steps.ts` |
| 43 | `parcours-souscription/parcours-souscription.feature` | Modification du souscripteur apres simulation invalide la cle de confirmation | souscription-service (brouillon) ; e2e navigateur | `src/app/wizard/draft.store.spec.ts`, `src/app/wizard/draft-sync.service.spec.ts`, `e2e/steps/parcours-souscription.steps.ts` |
| 44 | `confirmation-souscription/confirmation-souscription.feature` | Confirmation d'une demande complete | souscription-service + e2e | `e2e/journeys/full-journey.spec.ts`, `src/app/wizard/confirm.service.spec.ts`, `e2e/steps/confirmation-souscription.steps.ts` |
| 45 | `confirmation-souscription/confirmation-souscription.feature` | Affichage de la page de confirmation | e2e (affichage) ; SPA | `src/app/wizard/confirmation-step.component.spec.ts`, `e2e/steps/confirmation-souscription.steps.ts` |
| 46 | `confirmation-souscription/confirmation-souscription.feature` | Le capital final est recalcule au moment de la confirmation | souscription-service (appel distant à souscription-simulation-service) | `e2e/steps/confirmation-souscription.steps.ts` (contre la gateway) |
| 47 | `confirmation-souscription/confirmation-souscription.feature` | Double soumission de la confirmation avec la meme cle | souscription-service (idempotence) + e2e (rejeu) | `e2e/journeys/double-submit.spec.ts`, `src/app/wizard/confirm.service.spec.ts`, `e2e/steps/confirmation-souscription.steps.ts` |
| 48 | `confirmation-souscription/confirmation-souscription.feature` | L'enregistrement est idempotent pour une cle de confirmation deja utilisee | souscription-service (idempotence) | `e2e/steps/confirmation-souscription.steps.ts` (contre la gateway) |
| 49 | `confirmation-souscription/confirmation-souscription.feature` | Cle de confirmation invalide | souscription-service ; SPA (erreurs serveur) | `src/app/wizard/summary-step.component.spec.ts`, `e2e/steps/confirmation-souscription.steps.ts` |
| 50 | `confirmation-souscription/confirmation-souscription.feature` | Conditions non acceptees | souscription-service ; SPA (UX) | `src/app/wizard/summary-step.component.spec.ts`, `e2e/steps/confirmation-souscription.steps.ts` |
| 51 | `confirmation-souscription/confirmation-souscription.feature` | Indisponibilite de l'enregistrement | souscription-service + e2e (503 puis rejeu) | `e2e/journeys/confirm-503.spec.ts`, `src/app/core/http/error.interceptor.spec.ts`, `e2e/steps/confirmation-souscription.steps.ts` |
| 52 | `confirmation-souscription/confirmation-souscription.feature` | Confirmation d'un parcours incomplet | souscription-service ; e2e (garde) | `src/app/wizard/wizard.guards.spec.ts`, `e2e/steps/confirmation-souscription.steps.ts` |
| 53 | `confirmation-souscription/confirmation-souscription.feature` | Confirmation d'un parcours sans simulation | souscription-service ; e2e (garde) | `src/app/wizard/wizard.guards.spec.ts`, `e2e/steps/confirmation-souscription.steps.ts` |
| 54 | `confirmation-souscription/confirmation-souscription.feature` | Recherche d'une souscription par une cle inconnue | souscription-service ; SPA (page d'erreur) | `src/app/api/subscription-api.service.spec.ts`, `src/app/core/error/error-page.component.spec.ts`, `e2e/steps/confirmation-souscription.steps.ts` |
| 55 | `confirmation-souscription/confirmation-souscription.feature` | Recherche d'une souscription enregistree par sa cle | souscription-service + e2e (affichage) | `src/app/api/subscription-api.service.spec.ts`, `e2e/steps/confirmation-souscription.steps.ts` |
| 56 | `confirmation-souscription/confirmation-souscription.feature` | Consultation de la confirmation sans souscription aboutie | e2e (garde) ; souscription-service | `src/app/wizard/wizard.guards.spec.ts`, `e2e/steps/confirmation-souscription.steps.ts` |
| 57 | `persistance-souscriptions/persistance-souscriptions.feature` | Creation du schema de la base | souscription-service | Suite d'acceptation souscription-service (rejeu de `PersistanceSouscriptionsSteps`) |
| 58 | `persistance-souscriptions/persistance-souscriptions.feature` | Enregistrement d'une souscription | souscription-service | Suite d'acceptation souscription-service (rejeu de `PersistanceSouscriptionsSteps`) |
| 59 | `persistance-souscriptions/persistance-souscriptions.feature` | Enregistrement repete avec la meme cle de confirmation | souscription-service | Suite d'acceptation souscription-service (rejeu de `PersistanceSouscriptionsSteps`) |
| 60 | `persistance-souscriptions/persistance-souscriptions.feature` | Conflit d'unicite concurrent sur la cle de confirmation | souscription-service | Suite d'acceptation souscription-service (rejeu de `PersistanceSouscriptionsSteps`) |
| 61 | `persistance-souscriptions/persistance-souscriptions.feature` | Echec technique a l'insertion | souscription-service | Suite d'acceptation souscription-service (rejeu de `PersistanceSouscriptionsSteps`) |
| 62 | `persistance-souscriptions/persistance-souscriptions.feature` | Script de schema absent | souscription-service | Suite d'acceptation souscription-service (rejeu de `PersistanceSouscriptionsSteps`) |
| 63 | `persistance-souscriptions/persistance-souscriptions.feature` | Base de donnees injoignable | souscription-service | Suite d'acceptation souscription-service (rejeu de `PersistanceSouscriptionsSteps`) |
| 64 | `persistance-souscriptions/persistance-souscriptions.feature` | Relecture d'une cle inexistante | souscription-service | Suite d'acceptation souscription-service (rejeu de `PersistanceSouscriptionsSteps`) |
| 65 | `persistance-souscriptions/persistance-souscriptions.feature` | Les donnees du produit et du souscripteur sont restituees a la relecture | souscription-service | Suite d'acceptation souscription-service (rejeu de `PersistanceSouscriptionsSteps`) |
| 66 | `securite-web/securite-web.feature` | Entetes de securite ajoutes a chaque reponse | souscription-gateway ; e2e (ressources statiques) | `e2e/steps/securite-web.steps.ts`, `deploy/smoke-headers.sh` |
| 67 | `securite-web/securite-web.feature` | L'encodage des requetes et des reponses est UTF-8 | souscription-gateway (encodage) | `e2e/steps/securite-web.steps.ts` (contre la gateway) |
| 68 | `securite-web/securite-web.feature` | Un jeton anti-CSRF est cree pour la session a l'acces d'une action | souscription-gateway (double-submit cookie) ; SPA | `src/app/api/csrf-api.service.spec.ts`, `e2e/steps/securite-web.steps.ts` |
| 69 | `securite-web/securite-web.feature` | Le jeton anti-CSRF existant est conserve | souscription-gateway (double-submit cookie) ; SPA | `src/app/core/http/xsrf.spec.ts`, `e2e/steps/securite-web.steps.ts` |
| 70 | `securite-web/securite-web.feature` | Soumission acceptee avec le jeton anti-CSRF attendu | souscription-gateway ; SPA (en-tête) | `src/app/core/http/xsrf.spec.ts`, `e2e/steps/securite-web.steps.ts` |
| 71 | `securite-web/securite-web.feature` | Soumission refusee en cas de jeton anti-CSRF incorrect | souscription-gateway | `e2e/steps/securite-web.steps.ts` (contre la gateway) |
| 72 | `securite-web/securite-web.feature` | Soumission refusee en l'absence de jeton anti-CSRF | souscription-gateway + e2e (jeton absent → /erreur/403) | `e2e/journeys/missing-csrf.spec.ts`, `e2e/steps/securite-web.steps.ts` |
| 73 | `securite-web/securite-web.feature` | Jeton anti-CSRF de plus de 100 caracteres refuse | souscription-gateway | `e2e/steps/securite-web.steps.ts` (contre la gateway) |
| 74 | `securite-web/securite-web.feature` | Methodes HTTP non autorisees | souscription-gateway (liste blanche des méthodes) | `e2e/steps/securite-web.steps.ts` (contre la gateway), `scripts/check-http-methods.mjs` |
| 75 | `securite-web/securite-web.feature` | Methodes HTTP autorisees | souscription-gateway (liste blanche des méthodes) | `e2e/steps/securite-web.steps.ts` (contre la gateway), `scripts/check-http-methods.mjs` |
| 76 | `securite-web/securite-web.feature` | Les ressources statiques ne requierent pas de jeton anti-CSRF | e2e (ressources statiques servies par l'hôte SPA) | `e2e/steps/securite-web.steps.ts`, `deploy/smoke-headers.sh` |
| 77 | `initialisation-application/initialisation-application.feature` | Demarrage nominal de l'application | services backend (souscription-service, souscription-catalogue-service, souscription-simulation-service, souscription-gateway) | Suites d'acceptation backend (rejeu de `InitialisationApplicationSteps`) |
| 78 | `initialisation-application/initialisation-application.feature` | Les permissions du repertoire de donnees sont restreintes | services backend (souscription-service) | Suite d'acceptation souscription-service (rejeu de `InitialisationApplicationSteps`) |
| 79 | `initialisation-application/initialisation-application.feature` | Le chemin de la base est lu depuis la propriete systeme | services backend (souscription-service) | Suite d'acceptation souscription-service (rejeu de `InitialisationApplicationSteps`) |
| 80 | `initialisation-application/initialisation-application.feature` | Le systeme de fichiers ne supporte pas les permissions POSIX | services backend (souscription-service) | Suite d'acceptation souscription-service (rejeu de `InitialisationApplicationSteps`) |
| 81 | `initialisation-application/initialisation-application.feature` | Echec d'initialisation de l'application | services backend (souscription-service, souscription-catalogue-service, souscription-simulation-service) | Suites d'acceptation backend (rejeu de `InitialisationApplicationSteps`) |
| 82 | `initialisation-application/initialisation-application.feature` | Acces aux services avant initialisation | services backend (souscription-service, souscription-catalogue-service, souscription-simulation-service) | Suites d'acceptation backend (rejeu de `InitialisationApplicationSteps`) |
| 83 | `initialisation-application/initialisation-application.feature` | Arret de l'application | services backend (souscription-service, souscription-catalogue-service, souscription-simulation-service, souscription-gateway) | Suites d'acceptation backend (rejeu de `InitialisationApplicationSteps`) |
| 84 | `initialisation-application/initialisation-application.feature` | Echec de liberation d'un pilote JDBC a l'arret | services backend (souscription-service) | Suite d'acceptation souscription-service (rejeu de `InitialisationApplicationSteps`) |
| 85 | `formulaire-souscription/formulaire-souscription.feature` | Valeurs par defaut du formulaire | SPA (valeurs par défaut) | `src/app/wizard/subscriber-step.component.spec.ts`, `src/app/wizard/simulation-step.component.spec.ts`, `e2e/steps/formulaire-souscription.steps.ts` |
| 86 | `formulaire-souscription/formulaire-souscription.feature` | Les valeurs nulles sont remplacees par une chaine vide | SPA (valeurs par défaut) | `src/app/wizard/repopulate.spec.ts`, `e2e/steps/formulaire-souscription.steps.ts` |
| 87 | `formulaire-souscription/formulaire-souscription.feature` | Les valeurs saisies sont conservees telles quelles | SPA (conservation de la saisie) | `src/app/wizard/draft.store.spec.ts`, `src/app/wizard/repopulate.spec.ts`, `e2e/steps/formulaire-souscription.steps.ts` |
| 88 | `formulaire-souscription/formulaire-souscription.feature` | Acceptation des informations par l'utilisateur | SPA (case d'acceptation) | `src/app/wizard/summary-step.component.spec.ts`, `e2e/steps/formulaire-souscription.steps.ts` |
<!-- allocation-table:end -->

## Scénarios intégralement alloués au backend (17)

Aucun de ces scénarios n'est orphelin : ils sont assertés hors du périmètre SPA.

- `persistance-souscriptions` (9) → **souscription-service** : « Creation du schema de la base »,
  « Enregistrement d'une souscription », « Enregistrement repete avec la meme cle de
  confirmation », « Conflit d'unicite concurrent sur la cle de confirmation », « Echec technique a
  l'insertion », « Script de schema absent », « Base de donnees injoignable », « Relecture d'une
  cle inexistante », « Les donnees du produit et du souscripteur sont restituees a la relecture ».
- `initialisation-application` (8) → **services backend** : « Demarrage nominal de
  l'application », « Les permissions du repertoire de donnees sont restreintes », « Le chemin de la
  base est lu depuis la propriete systeme », « Le systeme de fichiers ne supporte pas les
  permissions POSIX », « Echec d'initialisation de l'application », « Acces aux services avant
  initialisation », « Arret de l'application », « Echec de liberation d'un pilote JDBC a l'arret ».

## Contrats comportementaux transverses (rejoués à la fin de chaque plan qui les touche)

Les quatre domaines suivants traversent plusieurs vagues. Tout plan qui modifie une surface qu'ils
couvrent **rejoue le `.feature` complet** avant son commit final :

| Contrat | Commande de rejeu |
| --- | --- |
| `parcours-souscription` | `npx cucumber-js --config e2e/cucumber.cjs e2e/features/parcours-souscription.feature` + `npm run e2e:journeys` |
| `confirmation-souscription` | `npx cucumber-js --config e2e/cucumber.cjs e2e/features/confirmation-souscription.feature` + `npm run e2e:journeys` |
| `securite-web` | `npx cucumber-js --config e2e/cucumber.cjs e2e/features/securite-web.feature` + `npm run e2e:journeys` |
| `catalogue` | `npx cucumber-js --config e2e/cucumber.cjs e2e/features/catalogue.feature` |

Un scénario isolé se rejoue avec `--name "<titre exact>"` ; les titres ci-dessus sont la référence.

## Traitement des 6 écarts de couverture déclarés

| Écart | Traitement |
| --- | --- |
| §1 Rendu HTML des JSP vérifié uniquement indirectement | tests de composants + régression visuelle (plans 20-24) : `src/app/**/*.component.spec.ts`, `e2e/visual/*.spec.ts` |
| §2 Mapping `struts-config.xml` non vérifié | la spec de routage des 10 mappings (plan 11) : `src/app/app.routes.spec.ts` |
| §3 Accès concurrents non exprimables en Gherkin | tests de double clic et de sérialisation des mutations (plan 18) : `e2e/journeys/double-submit.spec.ts`, `src/app/wizard/draft-sync.service.spec.ts` |
| §4 Valeurs exactes du capital final non assertées | reste au backend (souscription-simulation-service) ; la SPA ne recalcule aucun montant (`npm run check:money`) |
| §5 Permissions POSIX / chemin de la base | hors périmètre SPA (services backend) |
| §6 Détail du schéma SQL | hors périmètre SPA (souscription-service) |

## Portail comportemental (plan 40, W8-T04)

`npm run e2e` exécute, en mode strict, les scénarios dont le tier nomme **SPA** ou **e2e**
(61 scénarios, 128 lignes d'exemples) ; le filtre est construit depuis ce tableau par
`e2e/allocation.cjs`.

## Rejeu complet (plan 41, W9-T01)

Les 88 scénarios sont rejoués dans la même exécution de pipeline :

| Tier | Commande | Scénarios | Lignes d'exemples | Implémentation |
| --- | --- | --- | --- | --- |
| SPA / e2e | `npm run e2e` (cucumber-js, strict) | 61 | 128 | `e2e/steps/*.steps.ts`, SPA de production dans Chromium |
| backend (gateway + services) | `mvn test` à la racine (Cucumber-JVM 7.18.0, JDK 8) | 27 | 39 | `src/test/java/fr/demo/souscription/acceptance/`, sur l'implémentation de référence legacy |

Le tier backend lit `features/` (inchangé) et sélectionne les 27 scénarios par ligne
(`@SelectFile` de `BackendAcceptanceTest`) ; `BackendAllocationTest` échoue si cette sélection
diffère des lignes de ce tableau dont le tier ne nomme ni **SPA** ni **e2e**.
`scripts/scenario-rate.mjs` fusionne les deux rapports dans `e2e/reports/scenario-rate.json`
(comptes par tier et par domaine) et `npm run check:rate` (branché dans `lint`) échoue dès que
`green < declared`. `npm run check:no-pending` échoue si un fichier `e2e/steps/*.steps.ts`, quel
qu'il soit, retourne `'pending'`. Les étapes de `resilience.steps.ts` et `telemetry.steps.ts`
sont rejouées par les scénarios d'exploitation de `e2e/operational/` (`npm run e2e:operational`),
hors des 88 scénarios déclarés.

## Règles

- Aucun scénario ni aucune step definition n'est supprimé ou désactivé.
- Depuis le plan 41, aucune quarantaine n'est admise : aucun scénario n'est ignoré, filtré par
  tag d'exclusion (skip, wip, quarantaine) ou laissé en attente ; un scénario rouge bloque le
  pipeline.

### Quarantaine

| Test | Motif | Plan de sortie |
| --- | --- | --- |
| _(vide)_ | | |
