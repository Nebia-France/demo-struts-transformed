/**
 * Initial field values of the legacy `SubscriptionForm` (an `ActionForm`), kept verbatim:
 * the identity fields start empty, the simulation starts at `1000,00` / `100,00` / `5` and the
 * acceptance checkbox starts unticked.
 *
 * This module holds no framework dependency on purpose: the defaults are the single source of
 * truth for the reactive forms, the component specs and the Gherkin runner alike.
 */
export const SUBSCRIBER_DEFAULTS: Record<'firstName' | 'lastName' | 'email' | 'birthDate', string> = {
  firstName: '',
  lastName: '',
  email: '',
  birthDate: '',
};

export const SIMULATION_DEFAULTS: Record<'initial' | 'monthly' | 'years', string> = {
  initial: '1000,00',
  monthly: '100,00',
  years: '5',
};

/** Former `SubscriptionForm.accepted`, a primitive `boolean` defaulting to false. */
export const ACCEPTED_DEFAULT = false;

/**
 * The `safe()` rule of the legacy form: a null value is held and rendered as an empty string,
 * never as the string `null`. Applied whenever the forms are patched from the draft.
 */
export function safeText(value: string | null | undefined): string {
  return value ?? '';
}
