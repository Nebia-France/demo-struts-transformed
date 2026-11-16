import { parseFrAmount, subtractAmounts } from './amount';

describe('parseFrAmount', () => {
  it('accepts the formats accepted by InputValidator.amount', () => {
    expect(parseFrAmount('1000')).toBe('1000');
    expect(parseFrAmount('1000,00')).toBe('1000.00');
    expect(parseFrAmount('0,5')).toBe('0.5');
    expect(parseFrAmount('999999')).toBe('999999');
    // The source regex accepts either separator; the comma is only the fr-FR habit.
    expect(parseFrAmount('1000.50')).toBe('1000.50');
  });

  it('refuses the formats refused by the source', () => {
    expect(parseFrAmount('1 000,00')).toBeNull(); // thousand separator
    expect(parseFrAmount('1\u00A0000,00')).toBeNull(); // non-breaking thousand separator
    expect(parseFrAmount('1000,000')).toBeNull(); // three decimals
    expect(parseFrAmount('1234567')).toBeNull(); // more than six integer digits
    expect(parseFrAmount('-100')).toBeNull();
    expect(parseFrAmount('abcd')).toBeNull();
    expect(parseFrAmount('')).toBeNull();
  });

  it('normalises surrounding non-breaking spaces before matching', () => {
    expect(parseFrAmount('\u00A01000,00\u00A0')).toBe('1000.00');
    expect(parseFrAmount('\u202F1000\u202F')).toBe('1000');
    expect(parseFrAmount('  250,25  ')).toBe('250.25');
  });

  it('keeps the amount a string, never a number', () => {
    expect(typeof parseFrAmount('1000,00')).toBe('string');
    expect(parseFrAmount('1000,10')).toBe('1000.10'); // trailing zero preserved
  });
});

describe('subtractAmounts (Simulation.getGains)', () => {
  it('subtracts two scale-2 decimals on integer cents', () => {
    expect(subtractAmounts('42000.00', '38500.00')).toBe('3500.00');
    expect(subtractAmounts('1000.05', '0.10')).toBe('999.95');
    expect(subtractAmounts('100', '0.01')).toBe('99.99');
  });

  it('keeps a negative difference signed, as BigDecimal.subtract did', () => {
    expect(subtractAmounts('100.00', '150.50')).toBe('-50.50');
  });
});
