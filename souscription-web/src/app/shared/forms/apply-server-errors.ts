import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import type { AbstractControl, FormGroup } from '@angular/forms';

/**
 * W5-T03: projection of the server `FieldErrors` map onto the reactive controls, replacing
 * `request.setAttribute("errors", errors)` and the `${errors.<field>}` bindings of
 * `souscripteur` / `simulation` views.
 *
 * The map keys are the control names (`firstName`, `lastName`, `email`, `birthDate`, `initial`,
 * `monthly`, `years`, `accepted`) plus `storage`, which was never a form field: it is listed in
 * the summary only and is never attached to a control. An unknown key is ignored silently.
 *
 * Messages are rendered verbatim: the server owns the wording, the SPA never reformulates it.
 */

/** Keys of the contract that carry no control; `storage` is the persistence refusal. */
export const NON_CONTROL_FIELDS: readonly string[] = ['storage'];

/** Validation key under which a server message is stored on a control. */
const SERVER_ERROR_KEY = 'server';

export function applyServerErrors(form: FormGroup, fields: Record<string, string>): void {
  for (const [name, message] of Object.entries(fields)) {
    if (NON_CONTROL_FIELDS.includes(name)) {
      continue;
    }
    form.get(name)?.setErrors({ [SERVER_ERROR_KEY]: message });
  }
}

/** Server message held by a control, or an empty string. */
export function serverErrorOf(control: AbstractControl | null | undefined): string {
  const message: unknown = control?.errors?.[SERVER_ERROR_KEY];
  return typeof message === 'string' ? message : '';
}

/** Messages of the summary, in contract order, including the non-control keys. */
export function serverErrorMessages(fields: Record<string, string>): readonly string[] {
  return Object.values(fields);
}

/** Drops the server message of every control, before a new submission revalidates server-side. */
export function clearServerErrors(form: FormGroup): void {
  for (const control of Object.values(form.controls)) {
    if (serverErrorOf(control) !== '') {
      control.updateValueAndValidity({ emitEvent: false });
    }
  }
}

/**
 * Clears a control's server message as soon as the user edits it, then lets the next server
 * revalidation decide again. `onCleared` removes the same entry from the summary.
 */
export function clearServerErrorOnEdit(
  form: FormGroup,
  onCleared: (name: string) => void,
): void {
  for (const [name, control] of Object.entries(form.controls)) {
    control.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => {
      // Angular revalidates the control on every edit, which already drops the `server` key;
      // the guard covers a server error set outside that cycle.
      if (serverErrorOf(control) !== '') {
        control.updateValueAndValidity({ emitEvent: false });
      }
      onCleared(name);
    });
  }
}
