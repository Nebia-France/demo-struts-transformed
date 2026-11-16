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
import type { SubscriberInput } from '../api/models';
import { errorPageUrlFor } from '../core/error/app-error';
import { toFrDate, toIsoDate } from '../core/locale/date';
import { DraftStore } from './draft.store';
import { DraftSyncService } from './draft-sync.service';
import {
  applyServerErrors,
  clearServerErrorOnEdit,
  clearServerErrors,
  serverErrorMessages,
  serverErrorOf,
} from '../shared/forms/apply-server-errors';
import { ErrorSummaryComponent } from '../shared/forms/error-summary.component';
import { safeText } from './form-defaults';
import { serverFieldErrors, subscriberFormGroup } from './form-models';
import { subscriberPatch } from './repopulate';
import { WIZARD_LABELS } from './labels';
import { markStepClick, measureStepPaint } from '../core/telemetry/step-marks';

/**
 * Port of the `souscripteur` view: the legacy form binding on `SubscriptionForm` becomes a typed
 * reactive group, and the POST on `/souscripteur` becomes `DraftSyncService.saveSubscriber`
 * followed by the navigation to the simulation step.
 *
 * Entering the step repopulates the group from the stored draft, which replaces the GET branch
 * of `/souscripteur` refilling the `ActionForm` from the session. The birth date is held as
 * `dd/MM/yyyy` on screen and transported as ISO, so the conversion happens at both edges.
 *
 * No hidden CSRF input is rendered: the token is carried by the HTTP interceptor.
 */
@Component({
  selector: 'app-subscriber-step',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, ErrorSummaryComponent],
  templateUrl: './subscriber-step.component.html',
  styleUrl: './subscriber-step.component.scss',
})
export class SubscriberStepComponent {
  /** W4-T08: all rendered literals come from the feature label module. */
  readonly labels = WIZARD_LABELS;

  private readonly drafts = inject(DraftSyncService);
  private readonly store = inject(DraftStore);
  private readonly router = inject(Router);

  protected readonly form = subscriberFormGroup(inject(NonNullableFormBuilder));

  /** Field messages returned by the server, former `${errors.<field>}`. */
  private readonly errors = signal<Record<string, string>>({});

  /** Summary block of the `errors` fragment: every server message, verbatim and in contract order. */
  protected readonly summary = computed(() => serverErrorMessages(this.errors()));

  protected readonly productName = computed(() => this.store.product()?.name ?? '');
  protected readonly inFlight = this.drafts.subscriberInFlight;

  /**
   * W5-T05: explicit async state of the step. `pending` disables the submit control, `error`
   * means the failure is rendered in page with the input preserved, `success` means the draft
   * was saved and the step navigated on.
   */
  readonly status = signal<'idle' | 'pending' | 'error' | 'success'>('idle');

  constructor() {
    // W7-T02: closes the `souscripteur` transition started by `DraftSyncService.start`.
    afterNextRender(() => measureStepPaint('souscripteur'));
    const patch = subscriberPatch({ subscriber: this.store.subscriber(), simulation: null });
    this.form.patchValue({
      firstName: safeText(patch.firstName),
      lastName: safeText(patch.lastName),
      email: safeText(patch.email),
      birthDate: safeText(toFrDate(patch.birthDate)),
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

  protected hasError(control: string): boolean {
    const field = this.form.get(control);
    return this.serverError(control) !== '' || (!!field?.invalid && field.touched);
  }

  /** Drops an edited control's message from the summary, as its control error was cleared. */
  private forget(name: string): void {
    this.errors.update((current) => {
      const { [name]: removed, ...rest } = current;
      return removed === undefined ? current : rest;
    });
  }

  /**
   * Field messages are rendered in place and the input is preserved; only the `http` family
   * leaves the step for `/erreur/:status`, as the legacy `<error-page>` forwards did.
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

  /** Step 2 transition: the subscriber is persisted server-side before moving on. */
  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.status.set('error');
      return;
    }
    const value = this.form.getRawValue();
    const subscriber: SubscriberInput = {
      firstName: value.firstName,
      lastName: value.lastName,
      email: value.email,
      birthDate: toIsoDate(value.birthDate) ?? value.birthDate,
    };
    this.errors.set({});
    clearServerErrors(this.form);
    this.status.set('pending');
    markStepClick('simulation');
    this.drafts.saveSubscriber(subscriber).subscribe({
      next: () => {
        this.status.set('success');
        void this.router.navigate(['/souscription/simulation']);
      },
      error: (failure: unknown) => this.showFailure(failure),
    });
  }
}
