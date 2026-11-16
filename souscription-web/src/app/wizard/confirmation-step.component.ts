import {
  ChangeDetectionStrategy,
  Component,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { subtractAmounts } from '../core/locale/amount';
import { toParisDateTimeLabel } from '../core/locale/date';
import { toPercent } from '../core/locale/rate';
import { SimulationResultComponent } from '../shared/simulation-result.component';
import { DraftStore } from './draft.store';
import { safeText } from './form-defaults';
import { WIZARD_LABELS } from './labels';
import { measureStepPaint } from '../core/telemetry/step-marks';

/**
 * Port of the `confirmation` view, fed by the confirmed subscription held in the store (former
 * `session.getAttribute("completed")`). The creation instant is rendered as
 * `dd/MM/yyyy à HH:mm` in Europe/Paris, like `Subscription.getCreatedAtLabel()`.
 *
 * When no confirmed subscription exists the component renders nothing: the source answered
 * `restart()` on the confirmation path in that case, which `requireConfirmation` reproduces at
 * the route level, so this template is never the place where a missing confirmation is invented.
 */
@Component({
  selector: 'app-confirmation-step',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, SimulationResultComponent],
  templateUrl: './confirmation-step.component.html',
  styleUrl: './confirmation-step.component.scss',
})
export class ConfirmationStepComponent {
  /** W4-T08: all rendered literals come from the feature label module. */
  readonly labels = WIZARD_LABELS;

  private readonly store = inject(DraftStore);

  protected readonly subscription = this.store.completed;

  /**
   * W5-T05: explicit async state of the step. The confirmation screen runs no request of its
   * own — `requireConfirmation` has already resolved the subscription — so it is `success` as
   * soon as a confirmed subscription is in the store and `idle` otherwise.
   */
  readonly status = signal<'idle' | 'pending' | 'error' | 'success'>('idle');

  constructor() {
    // W7-T02: closes the `confirmation` transition started by the summary step.
    afterNextRender(() => measureStepPaint('confirmation'));
    if (this.store.completed() !== null) {
      this.status.set('success');
    }
  }

  protected readonly createdAtLabel = computed(() => {
    const createdAt = this.subscription()?.createdAt;
    return createdAt === undefined ? '' : toParisDateTimeLabel(createdAt);
  });

  protected readonly subscriberName = computed(() => {
    const subscriber = this.subscription()?.subscriber;
    return subscriber === undefined
      ? ''
      : `${safeText(subscriber.firstName)} ${safeText(subscriber.lastName)}`;
  });

  protected readonly ratePercent = computed(() => {
    const annualRate = this.subscription()?.product.annualRate;
    return annualRate === undefined ? '' : toPercent(annualRate);
  });

  /** Gains of the result block: capital at term less the total paid in, at scale 2. */
  protected readonly gains = computed(() => {
    const simulation = this.subscription()?.simulation;
    return simulation === undefined
      ? ''
      : subtractAmounts(simulation.finalCapital, simulation.contributed);
  });
}
