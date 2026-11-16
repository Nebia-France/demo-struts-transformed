/**
 * Labels of the error feature (W4-T09 / W4-T08).
 *
 * The four markup-bearing error keys of `src/main/resources/ApplicationResources.properties`
 * are NOT ported: the `<div class="message error"><ul>` / `<li>` wrappers they carried are now
 * the static structure of `app-error-summary`, and the messages themselves arrive as plain text
 * from the FieldErrors contract and are rendered with `{{ }}`. No server-provided text is ever
 * interpreted as HTML.
 */
export const ERROR_LABELS = {
  /** Replaces the sentence that preceded the error list of the JSP fragment. */
  summaryTitle: 'Votre demande ne peut pas être validée :',
  pageTitle: 'Information',
  heading: 'Page indisponible',
  notFound: "La page demandée n'existe pas ou n'est plus disponible.",
  generic: 'Votre demande ne peut pas être traitée pour le moment.',
  codeLabel: 'Code',
  backToCatalogue: 'Retour au catalogue',
} as const;
