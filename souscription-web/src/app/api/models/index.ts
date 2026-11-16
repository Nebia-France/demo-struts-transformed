/**
 * Hand-named aliases over the generated OpenAPI schema.
 *
 * Wire-format invariants enforced by the contract and relied upon here:
 * - every monetary or rate value (`initialAmount`, `monthlyAmount`, `contributed`,
 *   `finalCapital`, `annualRate`) is a decimal transported as a `string`, never a
 *   `number`: IEEE-754 would silently lose the scale of the legacy BigDecimal values;
 * - every date or instant (`birthDate`, `createdAt`) is an ISO-8601 `string`, never a
 *   `Date`: parsing and dd/MM/yyyy rendering happen in the presentation layer.
 *
 * Only types are generated; the HTTP services are hand-written.
 */
import type { components } from './schema';

export type ProductSummary = components['schemas']['ProductSummary'];
export type ProductDetail = components['schemas']['ProductDetail'];
export type SubscriberInput = components['schemas']['SubscriberInput'];
export type SimulationInput = components['schemas']['SimulationInput'];
export type SimulationResult = components['schemas']['SimulationResult'];
export type DraftView = components['schemas']['DraftView'];
export type ConfirmationRequest = components['schemas']['ConfirmationRequest'];
export type SubscriptionConfirmation = components['schemas']['SubscriptionConfirmation'];
export type FieldErrors = components['schemas']['FieldErrors'];

export type DraftStatus = DraftView['status'];
export type FieldError = NonNullable<FieldErrors['errors']>[number];
