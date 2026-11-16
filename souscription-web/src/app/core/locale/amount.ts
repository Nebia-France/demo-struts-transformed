/**
 * Input side of the fr-FR locale: the reverse direction of the `fmt:formatNumber` display.
 *
 * The accept/reject rules are those of `InputValidator.amount(String)`:
 * `[0-9]{1,6}(?:[.,][0-9]{1,2})?`, i.e. at most six integer digits, at most two decimals, and
 * either `,` or `.` as the decimal separator. A thousand separator, a sign, a third decimal or
 * anything non-numeric is refused. The value is handed over with a decimal point because every
 * amount travels as a decimal string (plan 06); it is never converted to a `number`, so no
 * IEEE-754 rounding can occur client-side. The server stays authoritative and revalidates.
 */
const AMOUNT = /^\d{1,6}(?:[.,]\d{1,2})?$/;

/** Outer whitespace only, including the non-breaking spaces `Intl` emits in fr-FR. */
const OUTER_SPACE = /^[\s\u00A0\u202F]+|[\s\u00A0\u202F]+$/g;

/**
 * Normalises a user-typed amount, or returns `null` when the format is refused.
 * Leading and trailing (non-breaking) spaces are stripped; an inner space is a thousand
 * separator and is refused, exactly like the source regex.
 */
export function parseFrAmount(raw: string): string | null {
  const trimmed = raw.replace(OUTER_SPACE, '');
  return AMOUNT.test(trimmed) ? trimmed.replace(',', '.') : null;
}

/**
 * Difference of two scale-2 decimal strings, computed on integer cents with `BigInt` so no
 * IEEE-754 rounding can occur client-side. Used for the `gains` row of the result block, which
 * the legacy `Simulation.getGains()` derived the same way (`finalCapital - contributed`).
 */
export function subtractAmounts(left: string, right: string): string {
  const cents = toCents(left) - toCents(right);
  const sign = cents < 0n ? '-' : '';
  const absolute = cents < 0n ? -cents : cents;
  return `${sign}${absolute / 100n}.${String(absolute % 100n).padStart(2, '0')}`;
}

function toCents(value: string): bigint {
  const [units, decimals = ''] = value.split('.');
  return BigInt(units) * 100n + BigInt(`${decimals}00`.slice(0, 2));
}
