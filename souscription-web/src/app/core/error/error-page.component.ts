import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ERROR_LABELS } from './labels';
import { errorMessage } from './error-catalog';

/**
 * Single page serving the 7 `<error-page>` cases of `web.xml` through the route `/erreur/:code`,
 * and the `response.sendError(404)` of the front controller. Port of the `erreur` view: the header and
 * the frozen step rail come from `app-layout` (route `data.step = 0`), as the JSP includes did.
 */
@Component({
  selector: 'app-error-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './error-page.component.html',
  styleUrl: './error-page.component.scss',
})
export class ErrorPageComponent {
  /** W4-T08: all rendered literals come from the feature label module. */
  readonly labels = ERROR_LABELS;

  /** Bound from the `:code` route parameter by `withComponentInputBinding()`. */
  readonly code = input.required<string>();

  /**
   * W4-T07/W4-T09: the message is looked up in the catalogue, never built from the route
   * parameter or from a response body, so a crafted `:code` cannot reach the rendered sentence.
   * The code itself is still shown, escaped, through `{{ }}`.
   */
  readonly message = computed(() => errorMessage(this.code()));
}
