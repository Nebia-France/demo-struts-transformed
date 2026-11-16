/**
 * Fails the build if any monetary, rate or date field of the API models is declared
 * with a JavaScript `number` (IEEE-754 precision loss) or a `Date` instance.
 * Money and dates are transported as strings on the wire.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const modelsDir = resolve(import.meta.dirname, '../src/app/api/models');

const MONEY_FIELDS = [
  'initial',
  'initialAmount',
  'monthly',
  'monthlyAmount',
  'contributed',
  'gains',
  'finalCapital',
  'rate',
  'ratePercent',
  'annualRate',
  'amount',
];
const DATE_FIELDS = ['birthDate', 'createdAt'];

const moneyRe = new RegExp(`\\b(${MONEY_FIELDS.join('|')})\\??\\s*:\\s*number\\b`);
const dateRe = new RegExp(`\\b(${DATE_FIELDS.join('|')})\\??\\s*:\\s*(number|Date)\\b`);

const offenders = [];
const walk = (dir) => {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      walk(full);
      continue;
    }
    if (!full.endsWith('.ts')) continue;
    readFileSync(full, 'utf8')
      .split('\n')
      .forEach((line, i) => {
        if (moneyRe.test(line) || dateRe.test(line)) {
          offenders.push(`${full}:${i + 1}: ${line.trim()}`);
        }
      });
  }
};
walk(modelsDir);

if (offenders.length) {
  console.error(
    'Monetary or date field typed as number/Date in the API models.\n' +
      'Fix the contract upstream (type: string) and regenerate; do not hand-edit schema.ts.\n' +
      offenders.join('\n'),
  );
  process.exit(1);
}

console.log('check:money OK');
