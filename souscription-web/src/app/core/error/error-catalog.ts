/**
 * W5-T01: catalogue of the user-facing error messages.
 *
 * Replaces the 7 `<error-page>` mappings of `src/main/webapp/WEB-INF/web.xml` (400, 403, 404,
 * 405, 409, 500 and `java.lang.Throwable`), which all forwarded to the `erreur` view. The wording of
 * that page is kept: `default` carries its generic sentence, and any status that is not listed
 * here — including 500 and anything raised by the global handler — falls back to it. The error
 * payload returned by the API is never rendered, exactly as the JSP never exposed a technical
 * trace.
 */
export const ERROR_MESSAGES: Record<string, string> = {
  '400': 'Votre demande est incomplète ou mal formée.',
  '403': "Votre demande n'a pas pu être authentifiée. Veuillez recommencer.",
  '404': "La page demandée n'existe pas.",
  '405': "Cette action n'est pas autorisée.",
  '409': 'Votre demande a déjà été traitée.',
  '503': "L'enregistrement est momentanément indisponible.",
  /**
   * Transport failure of the `network` family, including the 10 s timeout of the interceptor.
   * It has no HTTP status, so it is keyed by family name; the message is retryable in place and
   * never leads to a browser error page.
   */
  network: 'La connexion a été interrompue. Veuillez réessayer.',
  default: 'Une erreur est survenue. Veuillez réessayer ultérieurement.',
};

/** Looks the message up, falling back to `default` for every unclassified status. */
export function errorMessage(code: string | null | undefined): string {
  return (code && ERROR_MESSAGES[code]) || ERROR_MESSAGES['default'];
}
