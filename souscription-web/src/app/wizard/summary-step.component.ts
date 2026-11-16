import {
  ChangeDetectionStrategy,
  Component,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { subtractAmounts } from '../core/locale/amount';
import { toFrDate } from '../core/locale/date';
import { toPercent } from '../core/locale/rate';
import { SimulationResultComponent } from '../shared/simulation-result.component';
import { ConfirmService } from './confirm.service';
import { DraftStore } from './draft.store';
import { ACCEPTED_DEFAULT, safeText } from './form-defaults';
import { ACCEPTANCE_REQUIRED, fieldMessages } from './form-models';
import { WIZARD_LABELS } from './labels';
import { markStepClick, measureStepPaint } from '../core/telemetry/step-marks';

/**
 * Port of the `recapitulatif` view: the read-only recap of the product and the subscriber, the shared
 * result block of the `result` fragment, and the mandatory acceptance checkbox of the former
 * `html:form action="/confirmer"`.
 *
 * The refusal message of the acceptance box is the one the legacy `confirmer` branch produced,
 * carried by the FieldErrors contract (`ACCEPTANCE_REQUIRED`) rather than invented in the
 * template, so it reads identically to any other field error. The submit control is disabled
 * while the confirmation is in flight, so a double click cannot produce two subscriptions.
 * No hidden CSRF input and no `confirmationKey` input are rendered: the token is carried by the
 * HTTP interceptor and the key is replayed from the store by `ConfirmService`.
 */
@Component({
  selector: 'app-summary-step',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, SimulationResultComponent],
  templateUrl: './summary-step.component.html',
  styleUrl: './summary-step.component.scss',
})
export class SummaryStepComponent {
  /** W4-T08: all rendered literals come from the feature label module. */
  readonly labels = WIZARD_LABELS;

  private readonly store = inject(DraftStore);
  protected readonly confirmation = inject(ConfirmService);

  protected readonly form = inject(NonNullableFormBuilder).group({
    accepted: [ACCEPTED_DEFAULT],
  });

  /** Field messages of the step, former `${errors.accepted}`. */
  private readonly errors = signal<Record<string, string>>({});

  protected readonly product = this.store.product;
  protected readonly simulation = this.store.simulation;

  protected readonly subscriberName = computed(() => {
    const subscriber = this.store.subscriber();
    return subscriber === null
      ? ''
      : `${safeText(subscriber.firstName)} ${safeText(subscriber.lastName)}`;
  });
  protected readonly email = computed(() => safeText(this.store.subscriber()?.email));
  protected readonly birthDateLabel = computed(() =>
    safeText(toFrDate(safeText(this.store.subscriber()?.birthDate))),
  );

  protected readonly ratePercent = computed(() => {
    const annualRate = this.store.product()?.annualRate;
    return annualRate === undefined || annualRate === null ? '' : toPercent(annualRate);
  });

  /** Gains of the result block: capital at term less the total paid in, at scale 2. */
  protected readonly gains = computed(() => {
    const current = this.store.simulation();
    return current === null ? '' : subtractAmounts(current.finalCapital, current.contributed);
  });

  protected readonly acceptedError = computed(() => this.errors()['accepted'] ?? '');

  /**
   * W5-T05: explicit async state of the step. `pending` disables the submit control and shows a
   * progress affordance, `error` means the failure is rendered in page with the input preserved,
   * `success` means the confirmation went through and the step navigated away.
   */
  readonly status = signal<'idle' | 'pending' | 'error' | 'success'>('idle');

  constructor() {
    // W7-T02: closes the `recapitulatif` transition started by the simulation step.
    afterNextRender(() => measureStepPaint('recapitulatif'));
  }

  /** Former POST on `/confirmer`: the acceptance is checked first, exactly as the source did. */
  protected confirm(): void {
    if (!this.form.getRawValue().accepted) {
      this.errors.set(fieldMessages(ACCEPTANCE_REQUIRED));
      this.status.set('error');
      return;
    }
    this.errors.set({});
    this.status.set('pending');
    markStepClick('confirmation');
    void this.confirmation.confirm().then(() => {
      const failed =
        this.confirmation.retryable() !== null || this.confirmation.conflict() !== null;
      this.status.set(failed ? 'error' : 'success');
    });
  }
}
