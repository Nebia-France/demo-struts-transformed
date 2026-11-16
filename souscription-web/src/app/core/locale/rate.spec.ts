import { toPercent } from './rate';

describe('toPercent', () => {
  it('shifts the decimal string by two positions', () => {
    expect(toPercent('0.020000')).toBe('2.0000');
    expect(toPercent('0.060000')).toBe('6.0000');
  });

  it('handles a rate without decimals', () => {
    expect(toPercent('1')).toBe('100');
  });

  it('handles a rate with a single decimal', () => {
    expect(toPercent('0.5')).toBe('50');
  });
});
