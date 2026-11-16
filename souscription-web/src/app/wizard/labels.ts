/**
 * Labels of the wizard feature (W4-T08): the former `souscripteur`, `simulation`,
 * `recapitulatif` and `confirmation` views, plus the shared
 * `app-simulation-result` table ported from the `fragments/result` fragment.
 *
 * Every literal rendered by a wizard template comes from here and is written with `{{ }}`
 * or bound to an attribute, so it is escaped by Angular.
 */
export const WIZARD_LABELS = {
  subscriberRouteTitle: 'Identification du souscripteur',
  simulationRouteTitle: 'Simulation de votre placement',
  summaryRouteTitle: 'Récapitulatif de la demande',
  confirmationRouteTitle: 'Confirmation de la souscription',

  requiredMark: '*',
  previous: '< Précédent',
  continue: 'Continuer >',
  edit: 'Modifier',

  subscriberIntroBefore: 'Produit sélectionné :',
  subscriberIntroAfter: 'Renseignez les informations du souscripteur.',
  subscriberHeading: 'Vos informations personnelles',
  requiredLegend: 'Tous les champs sont obligatoires.',
  firstName: 'Prénom',
  lastName: 'Nom',
  email: 'Adresse électronique',
  emailHint: 'Exemple : prenom.nom@example.com',
  birthDate: 'Date de naissance',
  birthDateHint: 'JJ/MM/AAAA — réservé aux personnes de 18 ans et plus.',
  changeProduct: '< Changer de produit',

  simulationIntroBefore: 'Définissez votre projet pour',
  simulationIntroAfter: 'Vous pouvez recalculer la simulation avant de poursuivre.',
  simulationHeading: 'Paramètres du placement',
  simulationRequiredLegend:
    'Tous les champs sont obligatoires. Saisissez les montants sans séparateur de milliers.',
  initial: 'Versement initial',
  initialHint: 'De 100,00 à 100 000,00 €.',
  monthly: 'Versement mensuel',
  monthlyHint: 'De 0,00 à 2 000,00 €, en fin de mois.',
  duration: 'Durée',
  durationHint: 'Un nombre entier de 1 à 30 ans.',
  recalculate: 'Recalculer la simulation',
  estimationHeading: 'Votre estimation',
  euroSuffix: '€',
  yearsSuffix: 'an(s)',

  summaryIntro:
    'Vérifiez les informations ci-dessous avant de confirmer votre demande de souscription.',
  summaryProductHeading: 'Produit et souscripteur',
  summaryProjectHeading: "Votre projet d'épargne",
  rowProduct: 'Produit sélectionné',
  rowSubscriber: 'Souscripteur',
  rowEmail: 'Adresse électronique',
  rowBirthDate: 'Date de naissance',
  productProfileSeparator: '—',
  acceptance:
    "Je confirme l'exactitude des informations saisies et avoir pris connaissance des hypothèses de simulation et de l'absence de garantie de rendement.",
  confirmSubscription: 'Confirmer la souscription >',
  retry: 'Réessayer',

  confirmationSuccess: 'Votre demande de souscription a bien été enregistrée.',
  confirmationHeading: "Accusé d'enregistrement",
  rowReference: 'Référence de la demande',
  rowCreatedAt: 'Enregistrée le',
  timeZoneSuffix: '(Paris)',
  keepReference: 'Conservez la référence de votre demande.',
  backToCatalogue: 'Retour au catalogue',

  resultCaption: 'Résultat indicatif de la simulation',
  resultInitial: 'Versement initial',
  resultMonthly: 'Versement mensuel',
  resultDuration: 'Durée du placement',
  resultContributed: 'Total de vos versements',
  resultGains: 'Gains estimés, hors frais et fiscalité',
  resultFinalCapital: 'Capital final estimé',
  resultHypothesisBefore: 'Hypothèse constante de',
  resultHypothesisAfter:
    'par an, capitalisation mensuelle et versements en fin de mois. Hors frais, fiscalité et inflation. Rendement non garanti.',
  percentSuffix: '%',

  /**
   * Message under a field refused client-side (required, length, format), so a refusal always
   * says why whichever side detected it. The wording is the SPA's own: the server message
   * (`FieldErrors`) is never re-declared here, so the two tiers cannot drift
   * (`npm run check:labels:inventory`). A server message, when present, takes precedence.
   */
  fieldMessages: {
    firstName: 'Indiquez votre prénom, en 80 caractères au plus.',
    lastName: 'Indiquez votre nom, en 80 caractères au plus.',
    email: 'Indiquez votre adresse électronique, en 254 caractères au plus.',
    birthDate: 'Indiquez votre date de naissance au format JJ/MM/AAAA.',
    initial: 'Indiquez un montant de 100,00 à 100 000,00 €, avec deux décimales au plus.',
    monthly: 'Indiquez un montant de 0,00 à 2 000,00 €, avec deux décimales au plus.',
    years: "Indiquez un nombre entier d'années, de 1 à 30.",
  } as Readonly<Record<string, string>>,
} as const;
