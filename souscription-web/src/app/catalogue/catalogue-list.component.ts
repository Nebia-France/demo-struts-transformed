import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import type { ProductSummary } from '../api/models';
import { toPercent } from '../core/locale/rate';
import { CATALOGUE_LABELS } from './labels';

/** Row projection: the rate is pre-converted to percent, as the former `fmt:formatNumber` input. */
interface CatalogueRow {
  code: string;
  name: string;
  profile: string;
  ratePercent: string;
}

/**
 * Port of the `catalogue` view. The product rows come from the `products` route resolver
 * (`catalogueResolver`), so the component issues no HTTP call of its own; the `notice=parcours`
 * banner reproduces the `<c:if test="${param.notice == 'parcours'}">` block of the JSP.
 *
 * All server text is rendered with `{{ }}` interpolation, the equivalent of `<c:out>`.
 */
@Component({
  selector: 'app-catalogue-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DecimalPipe, RouterLink],
  templateUrl: './catalogue-list.component.html',
  styleUrl: './catalogue-list.component.scss',
})
export class CatalogueListComponent {
  /** W4-T08: all rendered literals come from the feature label module. */
  readonly labels = CATALOGUE_LABELS;

  private readonly route = inject(ActivatedRoute);

  private readonly data = toSignal(this.route.data, { initialValue: this.route.snapshot.data });

  private readonly queryParams = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });

  protected readonly products = computed<CatalogueRow[]>(() =>
    ((this.data()['products'] ?? []) as ProductSummary[]).map((product) => ({
      code: product.code,
      name: product.name,
      profile: product.profile,
      ratePercent: toPercent(product.annualRate),
    })),
  );

  /** Former `${param.notice == 'parcours'}`: the expired-journey redirect of the legacy session. */
  protected readonly parcoursNotice = computed(
    () => this.queryParams().get('notice') === 'parcours',
  );
}
