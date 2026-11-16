# Vocabulaire d'erreur gele (W3-T08)

Ce document fige le vocabulaire d'erreur champ par champ de `souscription-service`. Il remplace
les `ActionErrors` Struts et la `Map<String, String>` de `service/InputValidator` du source, qui
etait le contrat d'erreur de fait.

Le service renvoie une **cle de message** et un **code stable**, jamais un libelle francais : le
rendu des libelles appartient a `souscription-web`. Les memes cles doivent exister dans le bundle
de la SPA. Le bundle `ApplicationResources.properties` du source n'est pas porte : ses seules
cles restantes (`errors.header`, `errors.footer`, `errors.prefix`, `errors.suffix`,
`application.name`, `application.subtitle`) sont des fragments de rendu JSP sans equivalent REST.
Le bundle du service ne conserve que ce qui est journalise cote serveur.

La forme du corps est celle du schema gele `FieldErrors.json` (media type
`application/problem+json`) : `type`, `title`, `status`, `detail`, `instance`, `correlationId` et
un tableau `errors` d'objets `{field, message, code}` dans l'ordre de declaration des champs. Les
`ExceptionMapper` qui produisent ce corps sont installes par le plan 22 (AG-014, CFG-009) ; ce
document ne fige que le vocabulaire.

## Champs

Les neuf noms de champ du contrat, dans l'ordre de declaration :
`firstName`, `lastName`, `email`, `birthDate`, `initialAmount`, `monthlyAmount`, `durationYears`,
`accepted`, `storage`.

## Ordre (partie du contrat)

Le source accumulait les erreurs dans une `LinkedHashMap` : l'ordre de restitution etait donc
deterministe et fait partie du comportement observable (BASELINE.md 4.5). Le tableau `errors` du
schema gele `FieldErrors.json` est **ordonne** de la meme facon : d'abord les champs souscripteur
`firstName`, `lastName`, `email`, `birthDate`, puis les champs simulation `initialAmount`,
`monthlyAmount`, `durationYears`. Une implementation fondee sur une `HashMap` reordonnerait les
entrees d'une execution a l'autre : la SPA, qui place le focus sur la premiere erreur, deviendrait
imprevisible et les tests instables.

Les trois derniers ne sont pas des champs de saisie du contrat gele : `accepted` et `storage`
existent pour preserver la granularite source (case a cocher du recapitulatif et indisponibilite
de l'enregistrement).

## Table

| message key | source message | field | stable code |
| --- | --- | --- | --- |
| `subscriber.firstName.invalid` | `InputValidator.validateName` : prenom requis, 1 a 80 caracteres, sans caractere de controle | `firstName` | `FIRST_NAME_INVALID` |
| `subscriber.lastName.invalid` | `InputValidator.validateName` : nom requis, 1 a 80 caracteres, sans caractere de controle | `lastName` | `LAST_NAME_INVALID` |
| `subscriber.email.invalid` | `InputValidator.subscriberErrors` : adresse electronique valide, 254 caracteres maximum | `email` | `EMAIL_INVALID` |
| `subscriber.birthDate.format` | `InputValidator.subscriberErrors` : date valide au format jour/mois/annee (source) ; ISO-8601 `YYYY-MM-DD` sur le fil | `birthDate` | `BIRTH_DATE_FORMAT` |
| `subscriber.birthDate.future` | `InputValidator.subscriberErrors` : la date de naissance ne peut pas etre dans le futur | `birthDate` | `BIRTH_DATE_FUTURE` |
| `subscriber.birthDate.minor` | `InputValidator.subscriberErrors` : la souscription est reservee aux personnes majeures | `birthDate` | `BIRTH_DATE_MINOR` |
| `simulation.initialAmount.format` | `InputValidator.validateAmount` : deux decimales au maximum, sans separateur de milliers | `initialAmount` | `INITIAL_AMOUNT_FORMAT` |
| `simulation.initialAmount.range` | `InputValidator.simulationErrors` : versement initial entre 100 et 100000 EUR inclus | `initialAmount` | `INITIAL_AMOUNT_RANGE` |
| `simulation.monthlyAmount.format` | `InputValidator.validateAmount` : deux decimales au maximum, sans separateur de milliers | `monthlyAmount` | `MONTHLY_AMOUNT_FORMAT` |
| `simulation.monthlyAmount.range` | `InputValidator.simulationErrors` : mensualite entre 0 et 2000 EUR inclus | `monthlyAmount` | `MONTHLY_AMOUNT_RANGE` |
| `simulation.durationYears.range` | `InputValidator.simulationErrors` : duree entiere de 1 a 30 ans | `durationYears` | `DURATION_YEARS_RANGE` |
| `confirmation.accepted.required` | `SubscriptionAction` (~ligne 110) : validation des informations et des hypotheses de simulation requise | `accepted` | `ACCEPTANCE_REQUIRED` |
| `confirmation.storage.unavailable` | `SubscriptionAction` (~ligne 121) : enregistrement momentanement indisponible, aucune confirmation emise, saisie conservee | `storage` | `STORAGE_UNAVAILABLE` |

## Notes de portage

- Les deux messages de format (`*.format`) remplacent la normalisation source de la virgule
  decimale : le fil n'accepte que le point decimal et deux decimales exactes
  (`^[0-9]{1,6}\.[0-9]{2}$`). Un montant mal forme est une violation de contrainte, donc un
  **400** structure, jamais un 500.
- Les bornes (`*.range`) sont celles du source, inchangees ; elles sont aussi des contraintes
  `CHECK` de `V1__subscriptions.sql`. Leur application apres conversion en `BigDecimal` est le
  plan 21.
- `accepted` n'est pas porte par `ConfirmationRequest.json` : l'equivalent cible du refus
  `accepted=false` est l'absence ou l'invalidite de `confirmationKey`, qui produit un 400 avec le
  code `ACCEPTANCE_REQUIRED` ci-dessus.
- Le formatage francais des montants et des dates (separateurs, symbole monetaire, format de date
  local) est une preoccupation de presentation detenue par `souscription-web`.

## Parite regle par regle avec `service/InputValidator` (W7-T06)

Table de correspondance ligne a ligne : chaque regle imperative de la source et l'annotation qui
l'applique dans la cible. Un reviseur verifie la parite ici, sans lire les deux bases de code.

| Regle source (`service/InputValidator`) | Expression source | Annotation cible | Porteur |
| --- | --- | --- | --- |
| `validateName` — prenom requis | `value.trim().isEmpty()` | `@NotBlank` | `SubscriberInput.firstName` |
| `validateName` — prenom 80 caracteres maximum | `value.trim().length() > 80` | `@Size(max = 80)` | `SubscriberInput.firstName` |
| `validateName` — prenom sans caractere de controle | `value.codePoints().anyMatch(Character::isISOControl)` | `@NoControlChars` | `SubscriberInput.firstName` |
| `validateName` — nom requis | `value.trim().isEmpty()` | `@NotBlank` | `SubscriberInput.lastName` |
| `validateName` — nom 80 caracteres maximum | `value.trim().length() > 80` | `@Size(max = 80)` | `SubscriberInput.lastName` |
| `validateName` — nom sans caractere de controle | `value.codePoints().anyMatch(Character::isISOControl)` | `@NoControlChars` | `SubscriberInput.lastName` |
| `subscriberErrors` — adresse electronique requise | `form.getEmail().trim()` puis echec de l'expression | `@NotBlank` | `SubscriberInput.email` |
| `subscriberErrors` — 254 caracteres maximum | `email.length() > 254` | `@Size(max = 254)` | `SubscriberInput.email` |
| `subscriberErrors` — forme de l'adresse electronique | `email.matches("[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+")` | `@Pattern` avec **la meme expression, caractere pour caractere** (et non `@Email`, non equivalent sur `<` et `>`) | `SubscriberInput.email` |
| `subscriberErrors` — `trim()` prealable a tous les controles | `form.getEmail().trim()`, `value.trim()` | constructeur compact du record (`trimmed(...)`) | `SubscriberInput` |
| `birthDate` — date resoluble, calendrier strict | `DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(STRICT)` ; `birth == null` | `@Pattern(^[0-9]{4}-[0-9]{2}-[0-9]{2}$)` pour la forme ISO du fil + resolution stricte de `LocalDate.parse` dans `MinimumAgeTextValidator` (cle `subscriber.birthDate.format`) | `SubscriberInput.birthDate` |
| `subscriberErrors` — date non future | `birth.isAfter(today)` | `@MinimumAge(18)` → `MinimumAgeValidator` (cle `subscriber.birthDate.future`) | `SubscriberInput.birthDate` |
| `subscriberErrors` — majorite | `birth.isAfter(today.minusYears(18))` | `@MinimumAge(18)` → `MinimumAgeValidator` (cle `subscriber.birthDate.minor`) | `SubscriberInput.birthDate` |
| `amount` — montant present et bien forme | `raw == null \|\| !raw.matches("[0-9]{1,6}(?:[.,][0-9]{1,2})?")` | `@NotNull` + `@Pattern(^[0-9]{1,6}\.[0-9]{2}$)` — la virgule decimale n'est plus normalisee mais refusee en 400 (cle `*.format`) | `SimulationInput.initialAmount`, `SimulationInput.monthlyAmount` |
| `validateAmount` — versement initial entre 100 et 100000 | `compareTo(new BigDecimal("100")) < 0 \|\| compareTo(new BigDecimal("100000")) > 0` | `@DecimalRange(min = "100.00", max = "100000.00")` | `SimulationInput.initialAmount` |
| `validateAmount` — mensualite entre 0 et 2000 | `compareTo(new BigDecimal("0")) < 0 \|\| compareTo(new BigDecimal("2000")) > 0` | `@DecimalRange(min = "0.00", max = "2000.00")` | `SimulationInput.monthlyAmount` |
| `simulationErrors` — duree entiere de 1 a 30 ans | `form.getYears().matches("(?:[1-9]\|[12][0-9]\|30)")` | `@NotNull` + `@Min(1)` + `@Max(30)` sur un `Integer` (le type entier du contrat gele remplace le controle d'expression reguliere de la chaine) | `SimulationInput.durationYears` |
| `SubscriptionAction` — acceptation du recapitulatif | `accepted` du formulaire | `@NotNull UUID confirmationKey` (cle `confirmation.accepted.required`) | `ConfirmationRequest.confirmationKey` |
| Produit du parcours | code produit du formulaire | `@NotBlank` | `StartDraftRequest.productCode` |

Les trois ecarts de forme assumes et leur raison :

1. **Format de date** : `JJ/MM/AAAA` cote source, `YYYY-MM-DD` sur le fil. Le format local est rendu
   et analyse par `souscription-web` (ecart FG-005) ; la resolution stricte du calendrier est
   identique.
2. **Separateur decimal** : la source normalisait la virgule, la cible la refuse en 400. Le motif
   du contrat gele n'accepte que le point et deux decimales exactes.
3. **Duree** : chaine controlee par expression reguliere cote source, entier borne cote cible, le
   contrat gele declarant un entier.

### Fail-closed (W7-T06)

Deux comptes doivent valoir zero, et sont mesures et non revus :

- **0 point d'ecriture sans `@Valid`** : un `@Valid` oublie sur un corps de requete contourne la
  totalite de la table ci-dessus. `api/validation/ValidationCoverageTest` enumere par reflexion
  chaque methode `@POST` / `@PUT` / `@PATCH` / `@DELETE` du paquet `api` et exige `@Valid` sur
  chaque parametre de corps.
- **0 champ de DTO d'ecriture sans contrainte** : le meme test parcourt les composants de
  `SubscriberInput`, `SimulationInput`, `StartDraftRequest` et `ConfirmationRequest` et echoue sur
  un champ qui ne porte aucune contrainte Jakarta Validation.

La confirmation **rejoue** ces contraintes cote serveur (`ConfirmationService.revalidate`) sur le
contenu du brouillon lu en base : le client n'est jamais fait confiance, et la validation de la
saisie n'est pas une precondition de la confirmation. Un brouillon rendu invalide par un autre
chemin que la couche REST est refuse en 400 avec le **meme** corps `problem+json`, via la meme
`ConstraintViolationException` et le meme mappeur. Garde :
`application/ConfirmRevalidationTest`.
