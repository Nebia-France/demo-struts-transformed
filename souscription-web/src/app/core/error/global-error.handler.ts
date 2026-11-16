import { ErrorHandler, Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { TelemetryService } from '../telemetry/telemetry.service';
import { errorPageUrlFor } from './app-error';

/** Status rendered by `/erreur/:code` when nothing finer is known, former `java.lang.Throwable`
 * mapping of `web.xml` which forwarded to the `erreur` view. */
export const UNEXPECTED_ERROR_STATUS = 500;

/**
 * W5-T04: the single place an error that escaped every component lands.
 *
 * The legacy tier could not leave a blank screen: the 7 `<error-page>` mappings forwarded any
 * uncaught `Throwable` to the `erreur` view. A SPA has no such safety net, so this handler reproduces
 * it — every uncaught error is reported once and the user is routed to the error screen with
 * `skipLocationChange`, so the failing URL stays in the address bar and a reload retries it.
 *
 * In-page failures never reach here: `field`, `network`, `conflict` and the 503 of the summary
 * step are rendered by the step that raised them, with the user's input preserved.
 */
@Injectable()
export class GlobalErrorHandler implements ErrorHandler {
  private readonly router = inject(Router);
  private readonly telemetry = inject(TelemetryService, { optional: true });

  handleError(error: unknown): void {
    this.telemetry?.reportError(error);
    const url = errorPageUrlFor(error) ?? `/erreur/${UNEXPECTED_ERROR_STATUS}`;
    void this.router.navigateByUrl(url, { skipLocationChange: true });
  }
}
