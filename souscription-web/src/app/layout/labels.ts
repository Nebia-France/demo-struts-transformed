/**
 * Chrome labels of the layout feature (W4-T08).
 *
 * Replaces the inline literals of the `header` / `footer` fragments and the two non-markup keys of
 * `src/main/resources/ApplicationResources.properties` (`application.name`, `application.subtitle`).
 * The four `errors.*` keys of that file are deliberately not ported: they carried HTML markup,
 * which is now the structure of `app-error-summary` (see `core/error/labels.ts`).
 *
 * Every literal rendered by a layout template comes from here and is written with `{{ }}`,
 * so it is escaped by Angular.
 */
export const LAYOUT_LABELS = {
  skipLink: 'Aller au contenu',
  /** Announced by the `role="status"` live region on every route change. */
  routeAnnouncement: (pageTitle: string) => `Page : ${pageTitle}`,
  utilityExtranet: 'Extranet Financière Horizon',
  utilityService: 'Service épargne & placements',
  brandMark: 'FH',
  brandName: 'FINANCIÈRE HORIZON',
  brandBaseline: 'ÉPARGNE & PLACEMENTS',
  spaceTitle: 'Espace souscription',
  spaceSubtitle: 'Votre projet, étape par étape',
  mainNavLabel: 'Navigation principale',
  navCatalogue: 'Nos placements',
  navSubscription: 'Souscription en ligne',
  footerBrand: 'Financière Horizon — Épargne & placements',
  footerService: 'Souscription en ligne',
  /** Diagnostic release line: commit sha and build date of the deployed artifact. */
  footerRelease: (release: string, builtOn: string) => `Version ${release} du ${builtOn}`,
  breadcrumbHome: 'Accueil',
  breadcrumbSection: 'Épargne',
  breadcrumbSeparator: '>',
  stepsTitle: 'VOTRE SOUSCRIPTION',
  currentStepMarker: 'Étape actuelle : ',
  step1: 'Choix du produit',
  step2: 'Souscripteur',
  step3: 'Simulation',
  step4: 'Récapitulatif',
  step5: 'Confirmation',
  /** Former `default="Information"` of the `pageTitle` request attribute. */
  defaultPageTitle: 'Information',
} as const;
