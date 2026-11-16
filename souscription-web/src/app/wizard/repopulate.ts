import type { SimulationResult, SubscriberInput } from '../api/models';

/**
 * Repopulation of the wizard steps from the stored draft, replacing
 * `SubscriptionAction.populateSimulation` and the GET branch of `/souscripteur`, which refilled
 * the legacy `ActionForm` from the session draft.
 *
 * The values are returned verbatim as raw strings: a missing part of the draft becomes an empty
 * string, never `null` on screen (the `safe()` rule of the legacy `SubscriptionForm`). No
 * formatting happens here - the decimal-comma and date conversions belong to the locale module.
 */
export interface DraftSlice {
  subscriber: SubscriberInput | null;
  simulation: SimulationResult | null;
}

export interface SubscriberPatch {
  firstName: string;
  lastName: string;
  email: string;
  birthDate: string;
}

export interface SimulationPatch {
  initial: string;
  monthly: string;
  years: string;
}

export function subscriberPatch(draft: DraftSlice | null): SubscriberPatch {
  const subscriber = draft?.subscriber ?? null;
  return {
    firstName: subscriber?.firstName ?? '',
    lastName: subscriber?.lastName ?? '',
    email: subscriber?.email ?? '',
    birthDate: subscriber?.birthDate ?? '',
  };
}

export function simulationPatch(draft: DraftSlice | null): SimulationPatch {
  const simulation = draft?.simulation ?? null;
  return {
    initial: simulation?.initialAmount ?? '',
    monthly: simulation?.monthlyAmount ?? '',
    years: simulation?.durationYears != null ? String(simulation.durationYears) : '',
  };
}
