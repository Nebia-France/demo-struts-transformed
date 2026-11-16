/**
 * Display side of the annual rate: `annualRate` travels as a decimal string (`"0.060000"`)
 * and the legacy pages rendered it in percent with `fmt:formatNumber maxFractionDigits="0"`.
 *
 * The conversion shifts the decimal point by two positions on the string itself; the value is
 * never turned into a `number`, so no IEEE-754 rounding can occur before the `number` pipe
 * formats it for display.
 */
export function toPercent(annualRate: string): string {
  const negative = annualRate.startsWith('-');
  const unsigned = negative ? annualRate.slice(1) : annualRate;
  const [integer, fraction = ''] = unsigned.split('.');
  const digits = integer + fraction.padEnd(2, '0');
  const point = integer.length + 2;
  const shifted = `${digits.slice(0, point).replace(/^0+(?=\d)/, '')}.${digits.slice(point)}`;
  const trimmed = shifted.replace(/\.$/, '');
  return negative ? `-${trimmed}` : trimmed;
}
