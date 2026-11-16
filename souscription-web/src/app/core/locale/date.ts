/**
 * Date side of the fr-FR locale. Dates are displayed and entered as `dd/MM/yyyy`, which is what
 * `InputValidator.birthDate(String)` accepted, and transported as ISO `yyyy-MM-dd`, which is what
 * the API contract declares. Validation is strict, like
 * `DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(STRICT)`: `31/02/2024` is refused
 * instead of being shifted to a neighbouring day.
 */
const FR_DATE = /^(\d{2})\/(\d{2})\/(\d{4})$/;
const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

function isRealDate(year: number, month: number, day: number): boolean {
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day
  );
}

/** `dd/MM/yyyy` typed by the user to the ISO form sent to the gateway, or `null` if invalid. */
export function toIsoDate(ddMMyyyy: string): string | null {
  const parts = FR_DATE.exec(ddMMyyyy.trim());
  if (!parts) {
    return null;
  }
  const [, day, month, year] = parts;
  return isRealDate(parseInt(year, 10), parseInt(month, 10), parseInt(day, 10))
    ? `${year}-${month}-${day}`
    : null;
}

/** ISO date read from the API back to the `dd/MM/yyyy` form shown on screen. */
export function toFrDate(iso: string): string | null {
  const parts = ISO_DATE.exec(iso.trim());
  if (!parts) {
    return null;
  }
  const [, year, month, day] = parts;
  return isRealDate(parseInt(year, 10), parseInt(month, 10), parseInt(day, 10))
    ? `${day}/${month}/${year}`
    : null;
}

/**
 * Port of `Subscription.getCreatedAtLabel()`:
 * `DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm").withZone(ZoneId.of("Europe/Paris"))`
 * applied to the ISO-8601 instant carried by `SubscriptionConfirmation.createdAt`. The zone is
 * pinned to Europe/Paris, not the browser zone, so the label is the one the source rendered.
 * An unparsable instant yields an empty string, never the string `Invalid Date`.
 */
export function toParisDateTimeLabel(isoInstant: string): string {
  const instant = new Date(isoInstant);
  if (Number.isNaN(instant.getTime())) {
    return '';
  }
  const parts = new Intl.DateTimeFormat('fr-FR', {
    timeZone: 'Europe/Paris',
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(instant);
  const value = (type: Intl.DateTimeFormatPartTypes): string =>
    parts.find((part) => part.type === type)?.value ?? '';
  return `${value('day')}/${value('month')}/${value('year')} à ${value('hour')}:${value('minute')}`;
}
