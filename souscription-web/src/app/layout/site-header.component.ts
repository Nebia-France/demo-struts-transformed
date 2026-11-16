import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LAYOUT_LABELS } from './labels';

/**
 * Masthead part of the former `header` fragment: the `utility` band, the `masthead` header and
 * the `tabs` navigation. The implicit JSP `step` request attribute becomes a signal input.
 */
@Component({
  selector: 'app-site-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './site-header.component.html',
  styleUrl: './site-header.component.scss',
})
export class SiteHeaderComponent {
  readonly labels = LAYOUT_LABELS;

  /** Current wizard step, 1 (catalogue) to 5 (confirmation); 0 outside the funnel. */
  readonly step = input<number>(0);
}
