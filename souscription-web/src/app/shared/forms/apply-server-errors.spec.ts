import { TestBed } from '@angular/core/testing';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { errorPageUrlFor } from '../../core/error/app-error';
import {
  applyServerErrors,
  clearServerErrorOnEdit,
  serverErrorMessages,
  serverErrorOf,
} from './apply-server-errors';

/** Message of the source `ApplicationResources.properties`, kept verbatim end to end. */
const BIRTH_DATE_MESSAGE = 'La date de naissance doit respecter le format JJ/MM/AAAA.';

function group(): FormGroup {
  return new FormGroup({
    firstName: new FormControl('Camille', { nonNullable: true }),
    birthDate: new FormControl('12/04/1985', {
      nonNullable: true,
      validators: [Validators.required],
    }),
  });
}

describe('applyServerErrors', () => {
  it('ignores a key that carries no control', () => {
    const form = group();

    applyServerErrors(form, { unknownField: 'Message sans champ.' });

    expect(form.valid).toBe(true);
    expect(serverErrorOf(form.get('unknownField'))).toBe('');
  });

  it('keeps the server message verbatim on the control it belongs to', () => {
    const form = group();

    applyServerErrors(form, { birthDate: BIRTH_DATE_MESSAGE });

    expect(serverErrorOf(form.get('birthDate'))).toBe(BIRTH_DATE_MESSAGE);
    expect(form.get('birthDate')?.invalid).toBe(true);
  });

  it('never attaches `storage` to a control but lists it in the summary', () => {
    const form = group();
    const fields = { storage: 'La souscription ne peut pas être enregistrée.' };

    applyServerErrors(form, fields);

    expect(form.valid).toBe(true);
    for (const control of Object.values(form.controls)) {
      expect(serverErrorOf(control)).toBe('');
    }
    expect(serverErrorMessages(fields)).toEqual([
      'La souscription ne peut pas être enregistrée.',
    ]);
  });

  it('clears the server error of a control as soon as the user edits it', () => {
    const form = group();
    const forgotten: string[] = [];
    TestBed.runInInjectionContext(() => {
      clearServerErrorOnEdit(form, (name) => forgotten.push(name));
    });
    applyServerErrors(form, { birthDate: BIRTH_DATE_MESSAGE });

    form.get('birthDate')?.setValue('13/04/1985');

    expect(serverErrorOf(form.get('birthDate'))).toBe('');
    expect(form.get('birthDate')?.valid).toBe(true);
    expect(forgotten).toEqual(['birthDate']);
  });

  it('a field error never triggers a navigation', () => {
    expect(errorPageUrlFor({ kind: 'field', fields: { errors: [] } })).toBeNull();
  });
});
