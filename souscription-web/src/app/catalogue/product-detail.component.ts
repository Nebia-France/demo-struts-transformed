import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import type { ProductDetail } from '../api/models';
import { toPercent } from '../core/locale/rate';
import { DraftSyncService } from '../wizard/draft-sync.service';
import { CATALOGUE_LABELS } from './labels';

/**
 * Port of the `produit` view. The product comes from the `product` route resolver
 * (`productResolver`); the submit control replaces the `commencer` action POST by
 * `DraftSyncService.start(code)` followed by a navigation to the first wizard step.
 *
 * All server text is rendered with `{{ }}` interpolation, the equivalent of `<c:out>`.
 */
@Component({
  selector: 'app-product-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DecimalPipe, RouterLink],
  templateUrl: './product-detail.component.html',
  styleUrl: './product-detail.component.scss',
})
export class ProductDetailComponent {
  /** W4-T08: all rendered literals come from the feature label module. */
  readonly labels = CATALOGUE_LABELS;

  /** Bound from the `:code` route parameter by `withComponentInputBinding()`. */
  readonly code = input.required<string>();

  private readonly drafts = inject(DraftSyncService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  private readonly data = toSignal(this.route.data, { initialValue: this.route.snapshot.data });

  protected readonly product = computed(() => this.data()['product'] as ProductDetail | undefined);

  /** Annual rate in percent, as rendered by the former `fmt:formatNumber`. */
  protected readonly ratePercent = computed(() => {
    const annualRate = this.product()?.annualRate;
    return annualRate === undefined ? '' : toPercent(annualRate);
  });

  /** Former `/commencer`: opens the server-side draft, then enters the wizard. */
  start(): void {
    this.drafts
      .start(this.code())
      .subscribe(() => void this.router.navigate(['/souscription/souscripteur']));
  }
}
