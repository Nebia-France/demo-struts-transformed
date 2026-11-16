import { toFrDate, toIsoDate, toParisDateTimeLabel } from './date';

describe('locale dates', () => {
  it('converts the dd/MM/yyyy input to the ISO transport form', () => {
    expect(toIsoDate('29/02/2024')).toBe('2024-02-29');
    expect(toIsoDate('14/03/1985')).toBe('1985-03-14');
  });

  it('refuses malformed or non-existent dates, like the STRICT resolver', () => {
    expect(toIsoDate('31/02/2024')).toBeNull();
    expect(toIsoDate('29/02/2023')).toBeNull();
    expect(toIsoDate('1/3/1985')).toBeNull();
    expect(toIsoDate('1985-03-14')).toBeNull();
    expect(toIsoDate('')).toBeNull();
  });

  it('converts the ISO form back to the displayed dd/MM/yyyy', () => {
    expect(toFrDate('2024-02-29')).toBe('29/02/2024');
    expect(toFrDate('2024-02-30')).toBeNull();
    expect(toFrDate('29/02/2024')).toBeNull();
  });

  it('round-trips both directions', () => {
    expect(toFrDate(toIsoDate('29/02/2024') as string)).toBe('29/02/2024');
    expect(toIsoDate(toFrDate('1985-03-14') as string)).toBe('1985-03-14');
  });
});

describe('toParisDateTimeLabel (Subscription.getCreatedAtLabel)', () => {
  it('renders a UTC instant in Europe/Paris with the dd/MM/yyyy à HH:mm pattern', () => {
    expect(toParisDateTimeLabel('2024-05-01T09:30:00Z')).toBe('01/05/2024 à 11:30');
    // Winter offset: +01:00 instead of +02:00.
    expect(toParisDateTimeLabel('2024-01-15T23:45:00Z')).toBe('16/01/2024 à 00:45');
  });

  it('yields an empty label for an unparsable instant', () => {
    expect(toParisDateTimeLabel('pas-une-date')).toBe('');
  });
});
