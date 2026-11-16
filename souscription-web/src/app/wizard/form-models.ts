import { Validators } from '@angular/forms';
import type { AbstractControl, NonNullableFormBuilder, ValidationErrors } from '@angular/forms';
import type { FieldErrors } from '../api/models';
import { isAppError } from '../core/error/app-error';
import { parseFrAmount } from '../core/locale/amount';
import { toIsoDate } from '../core/locale/date';
import { SIMULATION_DEFAULTS, SUBSCRIBER_DEFAULTS } from './form-defaults';

/**
 * Typed replacement for `SubscriptionForm` and its legacy form bindings. Every control is a
 * non-nullable `string`, exactly like the legacy getters: no control ever holds `null`, so a
 * field is rendered as an empty string and never as `null` (the `safe()` rule).
 *
 * The simulation defaults are the source values verbatim — `initial` `1000,00`, `monthly`
 * `100,00`, `years` `5` — and are held in `form-defaults.ts`.
 *
 * The validators below are deliberately non-authoritative: they mirror rules the server already
 * enforces (`InputValidator`) so the user gets immediate feedback, and nothing more. The server
 * revalidates every submission and its field errors win.
 */

/** Mirror of the strict `dd/MM/yyyy` parsing of `InputValidator.birthDate`. */
export function frDatePattern(control: AbstractControl): ValidationErrors | null {
  const value = control.value as string;
  return value === '' || toIsoDate(value) !== null ? null : { frDate: true };
}

/** Mirror of the amount format of `InputValidator.amount`, through the locale parser. */
export function frAmountValidator(control: AbstractControl): ValidationErrors | null {
  const value = control.value as string;
  return value === '' || parseFrAmount(value) !== null ? null : { frAmount: true };
}

/** Mirror of the integer parsing of the legacy duration field. */
export function frYearsValidator(control: AbstractControl): ValidationErrors | null {
  const value = control.value as string;
  return value === '' || /^\d{1,2}$/.test(value) ? null : { frYears: true };
}

export function subscriberFormGroup(fb: NonNullableFormBuilder) {
  return fb.group({
    firstName: [SUBSCRIBER_DEFAULTS.firstName, [Validators.required, Validators.maxLength(80)]],
    lastName: [SUBSCRIBER_DEFAULTS.lastName, [Validators.required, Validators.maxLength(80)]],
    email: [SUBSCRIBER_DEFAULTS.email, [Validators.required, Validators.maxLength(254)]],
    birthDate: [SUBSCRIBER_DEFAULTS.birthDate, [Validators.required, frDatePattern]],
  });
}

export function simulationFormGroup(fb: NonNullableFormBuilder) {
  return fb.group({
    initial: [SIMULATION_DEFAULTS.initial, [Validators.required, frAmountValidator]],
    monthly: [SIMULATION_DEFAULTS.monthly, [Validators.required, frAmountValidator]],
    years: [SIMULATION_DEFAULTS.years, [Validators.required, frYearsValidator]],
  });
}

export type SubscriberFormGroup = ReturnType<typeof subscriberFormGroup>;
export type SimulationFormGroup = ReturnType<typeof simulationFormGroup>;

/**
 * Control name carrying each server field, so an RFC 7807 field error lands under the input it
 * belongs to. The simulation controls keep the legacy names (`initial`, `monthly`, `years`)
 * while the API names the same values `initialAmount`, `monthlyAmount` and `durationYears`.
 */
const CONTROL_OF_FIELD: Readonly<Record<string, string>> = {
  initialAmount: 'initial',
  monthlyAmount: 'monthly',
  durationYears: 'years',
};

/**
 * Field messages of a rejected submission, replacing the `${errors.<field>}` request attribute
 * the JSPs read. A non-validation failure yields no field message.
 */
export function serverFieldErrors(error: unknown): Record<string, string> {
  if (!isAppError(error) || error.kind !== 'field') {
    return {};
  }
  return fieldMessages(error.fields);
}

/** Field messages of an RFC 7807 problem document, keyed by the control that carries them. */
export function fieldMessages(problem: FieldErrors | null): Record<string, string> {
  const messages: Record<string, string> = {};
  for (const entry of problem?.errors ?? []) {
    messages[CONTROL_OF_FIELD[entry.field] ?? entry.field] = entry.message;
  }
  return messages;
}

/**
 * The acceptance refusal of the legacy `confirmer` branch
 * (`!form.isAccepted() -> errors.accepted`), expressed as the same RFC 7807 document the server
 * returns for any other rejected field, so the summary step renders it through the single
 * FieldErrors contract instead of a client-only message.
 */
export const ACCEPTANCE_REQUIRED: FieldErrors = {
  type: 'about:blank',
  title: 'Validation échouée',
  status: 400,
  errors: [
    {
      field: 'accepted',
      message: 'Vous devez valider les informations et les hypothèses de simulation.',
      code: 'acceptance.required',
    },
  ],
};
