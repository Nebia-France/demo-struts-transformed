import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LAYOUT_LABELS } from './labels';

/** Breadcrumb of the former `header` fragment: `Accueil > Épargne > ${pageTitle}`. */
@Component({
  selector: 'app-breadcrumb',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './breadcrumb.component.html',
  styleUrl: './breadcrumb.component.scss',
})
export class BreadcrumbComponent {
  readonly labels = LAYOUT_LABELS;

  /** Replaces the `pageTitle` request attribute; defaults as the JSP did. */
  readonly pageTitle = input<string>(LAYOUT_LABELS.defaultPageTitle);
}
