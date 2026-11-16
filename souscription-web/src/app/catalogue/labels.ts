/**
 * Labels of the catalogue feature (W4-T08): the former `catalogue` and `produit` views.
 * Every literal rendered by `catalogue-list.component.html` and
 * `product-detail.component.html` comes from here and is written with `{{ }}`.
 */
export const CATALOGUE_LABELS = {
  routeTitle: 'Catalogue des placements',
  productRouteTitle: 'Fiche produit',
  parcoursNotice:
    'Votre parcours est incomplet ou votre session a expiré. Veuillez choisir un produit pour commencer une nouvelle demande.',
  intro:
    'Choisissez le placement qui correspond à votre projet, puis réalisez une simulation avant de compléter votre demande de souscription.',
  listHeading: "Nos solutions d'épargne",
  listLead:
    'Comparez les trois profils de placement et consultez leurs caractéristiques avant de faire votre choix.',
  tableCaption: 'Comparaison des trois placements',
  columnProduct: 'Placement',
  columnProfile: 'Profil de risque',
  columnRate: 'Taux annuel',
  columnRateSecondLine: 'illustratif',
  columnFrom: 'À partir de',
  columnInformation: 'Information',
  minimumInitial: '100,00 €',
  consult: 'Consulter',
  consultChevron: '>',
  listFinePrint:
    'Les niveaux de risque sont indicatifs. Le capital et les rendements ne sont pas garantis.',
  howToHeading: 'Comment souscrire ?',
  howToStep1: 'Consultez la fiche du produit de votre choix.',
  howToStep2: 'Renseignez vos informations et simulez votre épargne.',
  howToStep3: 'Vérifiez votre récapitulatif et confirmez votre demande de souscription.',
  noticeTitle: 'Information importante',
  noticeBody:
    'Les projections reposent sur les hypothèses de calcul indiquées. Les performances futures ne sont pas garanties.',
  detailIntroBefore: 'Consultez les caractéristiques de',
  detailIntroAfter: 'avant de commencer votre demande.',
  detailHeadingSuffix: '— caractéristiques',
  rowProfile: 'Profil de risque',
  rowRate: 'Taux annuel nominal illustratif',
  rowInitial: 'Versement initial',
  rowInitialRange: 'De 100,00 à 100 000,00 €',
  rowMonthly: 'Versements mensuels',
  rowMonthlyRange: 'De 0,00 à 2 000,00 €',
  rowDuration: 'Durée simulée',
  rowDurationRange: 'De 1 à 30 ans',
  rowAge: "Condition d'âge",
  rowAgeValue: 'Souscripteur majeur',
  detailFinePrint:
    'La simulation repose sur un taux constant, sans garantie de capital ni de performance. Frais et fiscalité non pris en compte.',
  backToCatalogue: '< Retour au catalogue',
  startSubscription: 'Commencer la souscription >',
  percentSuffix: '%',
} as const;
