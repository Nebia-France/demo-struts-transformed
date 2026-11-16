import {
  ChangeDetectionStrategy,
  Component,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import type { SimulationInput, SimulationResult } from '../api/models';
import { SimulationApiService } from '../api/simulation-api.service';
import { errorPageUrlFor } from '../core/error/app-error';
import { parseFrAmount, subtractAmounts } from '../core/locale/amount';
import { toPercent } from '../core/locale/rate';
import {
  applyServerErrors,
  clearServerErrorOnEdit,
  clearServerErrors,
  serverErrorMessages,
  serverErrorOf,
} from '../shared/forms/apply-server-errors';
import { ErrorSummaryComponent } from '../shared/forms/error-summary.component';
import { SimulationResultComponent } from '../shared/simulation-result.component';
import { DraftStore } from './draft.store';
import { DraftSyncService } from './draft-sync.service';
import { safeText } from './form-defaults';
import { serverFieldErrors, simulationFormGroup } from './form-models';
import { simulationPatch } from './repopulate';
import { WIZARD_LABELS } from './labels';
import { markStepClick, measureStepPaint } from '../core/telemetry/step-marks';

/**
 * Port of the `simulation` view. The two `button name="command"` controls of the legacy form become
 * two distinct component actions, so the `command` request parameter and its unknown-value
 * branch disappear by construction:
 * - `recalculate()` (former `calculer`) projects the amounts and stays on the step;
 * - `continue()` (former `continuer`) persists them on the draft and moves to the summary.
 *
 * Amounts are entered and displayed with the fr-FR decimal comma and transported as decimal
 * strings with a dot; they are never converted to a `number`. No hidden CSRF input is rendered:
 * the token is carried by the HTTP interceptor.
 */
@Component({
  selector: 'app-simulation-step',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, SimulationResultComponent, ErrorSummaryComponent],
  templateUrl: './simulation-step.component.html',
  styleUrl: './simulation-step.component.scss',
})
export class SimulationStepComponent {
  /** W4-T08: all rendered literals come from the feature label module. */
  readonly labels = WIZARD_LABELS;

  private readonly drafts = inject(DraftSyncService);
  private readonly store = inject(DraftStore);
  private readonly router = inject(Router);
  private readonly simulations = inject(SimulationApiService);

  protected readonly form = simulationFormGroup(inject(NonNullableFormBuilder));

  /** Field messages returned by the server, former `${errors.<field>}`. */
  private readonly errors = signal<Record<string, string>>({});
  /** Projection of the last `recalculate()`; before that, the one stored on the draft. */
  private readonly recalculated = signal<SimulationResult | null>(null);

  /** Summary block of the `errors` fragment: every server message, verbatim and in contract order. */
  protected readonly summary = computed(() => serverErrorMessages(this.errors()));

  protected readonly productName = computed(() => this.store.product()?.name ?? '');
  protected readonly inFlight = this.drafts.simulationInFlight;

  /**
   * W5-T05: explicit async state of the step, shared by `recalculate()` and `continue()`.
   * `pending` disables both controls, `error` means the failure is rendered in page with the
   * entered amounts preserved, `success` means the draft was saved and the step navigated on.
   */
  readonly status = signal<'idle' | 'pending' | 'error' | 'success'>('idle');

  /** Former `<c:if test="${not empty draft.simulation and empty errors}">` result block. */
  protected readonly result = computed(() =>
    Object.keys(this.errors()).length > 0
      ? null
      : (this.recalculated() ?? this.store.simulation()),
  );

  protected readonly ratePercent = computed(() => {
    const annualRate = this.store.product()?.annualRate;
    return annualRate === undefined || annualRate === null ? '' : toPercent(annualRate);
  });

  /** Gains of the result block: capital at term less the total paid in, at scale 2. */
  protected readonly gains = computed(() => {
    const current = this.result();
    return current === null ? '' : subtractAmounts(current.finalCapital, current.contributed);
  });

  constructor() {
    // W7-T02: closes the `simulation` transition started by the subscriber step.
    afterNextRender(() => measureStepPaint('simulation'));
    const patch = simulationPatch({ subscriber: null, simulation: this.store.simulation() });
    this.form.patchValue({
      initial: toFrAmount(patch.initial) || this.form.controls.initial.value,
      monthly: toFrAmount(patch.monthly) || this.form.controls.monthly.value,
      years: safeText(patch.years) || this.form.controls.years.value,
    });
    clearServerErrorOnEdit(this.form, (name) => this.forget(name));
  }

  protected serverError(control: string): string {
    return serverErrorOf(this.form.get(control));
  }

  /** Message under `control`: the server's, else the client refusal of a touched control. */
  protected fieldError(control: string): string {
    const server = this.serverError(control);
    if (server !== '') {
      return server;
    }
    const field = this.form.get(control);
    return field?.invalid && field.touched ? (this.labels.fieldMessages[control] ?? '') : '';
  }

  /** Drops an edited control's message from the summary, as its control error was cleared. */
  private forget(name: string): void {
    this.errors.update((current) => {
      const { [name]: removed, ...rest } = current;
      return removed === undefined ? current : rest;
    });
  }

  protected hasError(control: string): boolean {
    const field = this.form.get(control);
    return this.serverError(control) !== '' || (!!field?.invalid && field.touched);
  }

  /** Former `command=calculer`: projects the amounts without leaving the step. */
  recalculate(): void {
    const input = this.input();
    if (input === null) {
      return;
    }
    this.errors.set({});
    clearServerErrors(this.form);
    this.status.set('pending');
    this.simulations
      .simulate({ productCode: this.store.product()?.code ?? '', ...input })
      .subscribe({
        next: (projection) => {
          this.recalculated.set(projection);
          this.status.set('success');
        },
        error: (failure: unknown) => this.showFailure(failure),
      });
  }

  /** Former `command=continuer`: the amounts are persisted server-side, then step 4. */
  continue(): void {
    const input = this.input();
    if (input === null) {
      return;
    }
    this.errors.set({});
    clearServerErrors(this.form);
    this.status.set('pending');
    markStepClick('recapitulatif');
    this.drafts.saveSimulation(input).subscribe({
      next: () => {
        this.status.set('success');
        void this.router.navigate(['/souscription/recapitulatif']);
      },
      error: (failure: unknown) => this.showFailure(failure),
    });
  }

  /**
   * Field messages are rendered in place and the entered amounts are preserved; only the `http`
   * family leaves the step for `/erreur/:status`, as the legacy `<error-page>` forwards did.
   */
  private showFailure(failure: unknown): void {
    const fields = serverFieldErrors(failure);
    this.errors.set(fields);
    applyServerErrors(this.form, fields);
    this.status.set('error');
    const url = errorPageUrlFor(failure);
    if (url !== null) {
      void this.router.navigateByUrl(url);
    }
  }

  /** Wire payload of the current group, or null when the group is refused client-side. */
  private input(): SimulationInput | null {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.status.set('error');
      return null;
    }
    const value = this.form.getRawValue();
    const initialAmount = parseFrAmount(value.initial);
    const monthlyAmount = parseFrAmount(value.monthly);
    if (initialAmount === null || monthlyAmount === null) {
      this.form.markAllAsTouched();
      this.status.set('error');
      return null;
    }
    return { initialAmount, monthlyAmount, durationYears: parseInt(value.years, 10) };
  }
}

/** Decimal string as stored on the wire to its fr-FR displayed form. */
function toFrAmount(value: string): string {
  return safeText(value).replace('.', ',');
}
