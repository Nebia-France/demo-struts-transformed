import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { ERROR_LABELS } from '../../core/error/labels';

/**
 * Port of the `errors` fragment. The messages are the server `FieldErrors` entries, rendered verbatim
 * through `{{ }}` so no server text is ever interpreted as HTML. The markup keeps
 * `id="erreurs"`, the `message error` classes and `role="alert"` so the accessibility
 * behaviour and the existing selectors are unchanged.
 */
@Component({
  selector: 'app-error-summary',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './error-summary.component.html',
  styleUrl: './error-summary.component.scss',
})
export class ErrorSummaryComponent {
  readonly labels = ERROR_LABELS;

  /** Validation messages, in contract order; empty renders nothing, as `c:if` did. */
  readonly serverErrors = input<readonly string[]>([]);
}
